package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.Follow;
import net.javahippie.fitpub.model.entity.RemoteActor;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.FollowRepository;
import net.javahippie.fitpub.repository.RemoteActorRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.security.HttpSignatureValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for ActivityPub federation operations.
 * Handles outbound activities and remote actor management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FederationService {

    private final RemoteActorRepository remoteActorRepository;
    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final HttpSignatureValidator signatureValidator;
    private final ObjectMapper objectMapper;
    // Injected via constructor (Lombok @RequiredArgsConstructor) so federation HTTP calls
    // go through the application-wide RestTemplate bean — which carries connect/socket
    // /response timeouts. Previously this was `new RestTemplate()` with no timeouts at all.
    private final RestTemplate restTemplate;

    /**
     * Self-reference for invoking {@code @Async} methods on this service from other
     * methods in the same class. Spring's AOP proxy is bypassed when you call
     * {@code this.someAsyncMethod()} directly, so any {@code @Async} call from inside
     * the class must go through this proxy reference. {@code @Lazy} avoids the
     * circular constructor injection that would otherwise happen.
     */
    @Autowired
    @Lazy
    private FederationService self;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    /**
     * Fetch and cache a remote actor's information.
     *
     * @param actorUri the actor's URI
     * @return the cached remote actor
     */
    @Transactional
    public RemoteActor fetchRemoteActor(String actorUri) {
        log.info("Fetching remote actor: {}", actorUri);

        // Check if we have a cached version
        RemoteActor cached = remoteActorRepository.findByActorUri(actorUri).orElse(null);
        if (cached != null && cached.getLastFetchedAt() != null &&
            cached.getLastFetchedAt().isAfter(Instant.now().minusSeconds(3600))) {
            log.debug("Using cached actor info for: {}", actorUri);
            return cached;
        }

        try {
            // Fetch actor information
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/activity+json");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                actorUri,
                HttpMethod.GET,
                entity,
                Map.class
            );

            Map<String, Object> actorData = response.getBody();
            if (actorData == null) {
                throw new RuntimeException("Empty actor response from: " + actorUri);
            }

            // Parse actor data
            String username = extractUsername(actorUri, actorData);
            String domain = URI.create(actorUri).getHost();
            String inboxUrl = (String) actorData.get("inbox");
            String outboxUrl = (String) actorData.get("outbox");
            String sharedInboxUrl = extractSharedInbox(actorData);
            String publicKey = extractPublicKey(actorData);
            String publicKeyId = extractPublicKeyId(actorData);

            // Update or create remote actor
            RemoteActor actor;
            if (cached != null) {
                actor = cached;
            } else {
                actor = new RemoteActor();
                actor.setActorUri(actorUri);
            }

            actor.setUsername(username);
            actor.setDomain(domain);
            actor.setInboxUrl(inboxUrl);
            actor.setOutboxUrl(outboxUrl);
            actor.setSharedInboxUrl(sharedInboxUrl);
            actor.setPublicKey(publicKey);
            actor.setPublicKeyId(publicKeyId);
            actor.setDisplayName((String) actorData.get("name"));
            actor.setAvatarUrl(extractAvatarUrl(actorData));
            actor.setSummary((String) actorData.get("summary"));
            actor.setLastFetchedAt(Instant.now());

            return remoteActorRepository.save(actor);

        } catch (Exception e) {
            log.error("Failed to fetch remote actor: {}", actorUri, e);
            throw new RuntimeException("Failed to fetch remote actor: " + actorUri, e);
        }
    }

    /**
     * Record a follow request to a remote actor.
     *
     * <p>Synchronously: validates the remote actor exists (via {@link #fetchRemoteActor},
     * which is cached so subsequent calls are fast), then writes a local PENDING
     * {@code Follow} row with a freshly-generated activity ID.
     *
     * <p>Asynchronously: hands off the actual HTTP-signed Follow delivery to
     * {@link #deliverFollowActivityAsync} on the {@code taskExecutor} thread pool.
     * The user's HTTP response no longer waits for the federated server to ack —
     * if the remote is briefly unreachable the local PENDING row remains and a
     * future delivery retry could pick it up.
     *
     * <p>This is a behaviour change vs. the previous version, which sent the HTTP
     * activity first and only saved the local row on success. The new ordering means
     * a follow attempt to a temporarily unreachable server is preserved locally
     * instead of failing the user's request entirely.
     *
     * @param remoteActorUri the URI of the remote actor to follow
     * @param localUser the local user initiating the follow
     */
    @Transactional
    public void sendFollowActivity(String remoteActorUri, User localUser) {
        log.info("Recording Follow request from {} to {}", localUser.getUsername(), remoteActorUri);

        // 1. Validate remote actor exists. If the URI is bogus or the remote is hard
        //    unreachable on a cold cache, this throws and the controller surfaces it
        //    to the user. Cached lookups make this near-instant for re-follows.
        RemoteActor remoteActor = fetchRemoteActor(remoteActorUri);

        // 2. Save the local PENDING follow row immediately so the user's intent is
        //    persisted even if HTTP delivery later fails. The status flips to ACCEPTED
        //    when we receive the Accept activity in the inbox.
        String followId = baseUrl + "/activities/follow/" + UUID.randomUUID();
        Follow follow = Follow.builder()
            .followerId(localUser.getId())
            .followingActorUri(remoteActorUri)
            .status(Follow.FollowStatus.PENDING)
            .activityId(followId)
            .build();
        followRepository.save(follow);

        // 3. Fire the actual HTTP delivery off the request thread. Errors inside
        //    the async helper are logged but cannot affect the local row.
        self.deliverFollowActivityAsync(followId, remoteActorUri, remoteActor.getInboxUrl(), localUser);
    }

    /**
     * Background helper for {@link #sendFollowActivity}: builds the ActivityPub
     * Follow envelope and POSTs it to the remote actor's inbox. Runs on the
     * {@code taskExecutor} thread pool. Failures are logged and swallowed; the
     * caller has already returned by the time this runs.
     *
     * <p>Must be invoked through the {@link #self} proxy reference (not via
     * {@code this}) so the {@code @Async} aspect actually applies.
     */
    @Async("taskExecutor")
    public void deliverFollowActivityAsync(String followId, String remoteActorUri, String inboxUrl, User localUser) {
        try {
            String actorUri = baseUrl + "/users/" + localUser.getUsername();

            Map<String, Object> followActivity = new HashMap<>();
            followActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            followActivity.put("type", "Follow");
            followActivity.put("id", followId);
            followActivity.put("actor", actorUri);
            followActivity.put("object", remoteActorUri);
            followActivity.put("published", Instant.now().toString());

            sendActivity(inboxUrl, followActivity, localUser);
            log.info("Follow activity delivered: {} -> {}", localUser.getUsername(), remoteActorUri);
        } catch (Exception e) {
            log.error("Failed to deliver Follow activity from {} to {}", localUser.getUsername(), remoteActorUri, e);
        }
    }

    /**
     * Send an Accept activity in response to a Follow.
     *
     * <p>Runs on the {@code taskExecutor} pool. The inbox handler that triggers this
     * needs to ack the federated sender with 202 quickly; we don't want the ack to
     * wait on another HTTP roundtrip back to the sender's inbox.
     *
     * @param follow the follow relationship
     * @param localUser the local user being followed
     */
    @Async("taskExecutor")
    public void sendAcceptActivity(Follow follow, User localUser) {
        try {
            // Get the remote actor who sent the follow request
            String remoteActorUri = follow.getRemoteActorUri();
            if (remoteActorUri == null) {
                log.error("Cannot send Accept: Follow has no remote actor URI");
                return;
            }

            RemoteActor remoteActor = fetchRemoteActor(remoteActorUri);

            String acceptId = baseUrl + "/activities/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + localUser.getUsername();

            Map<String, Object> acceptActivity = new HashMap<>();
            acceptActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            acceptActivity.put("type", "Accept");
            acceptActivity.put("id", acceptId);
            acceptActivity.put("actor", actorUri);
            acceptActivity.put("object", follow.getActivityId());

            sendActivity(remoteActor.getInboxUrl(), acceptActivity, localUser);
            log.info("Sent Accept activity to: {}", remoteActor.getActorUri());

        } catch (Exception e) {
            log.error("Failed to send Accept activity for follow: {}", follow.getId(), e);
        }
    }

    /**
     * Send an activity to a remote inbox.
     *
     * @param inboxUrl the remote inbox URL
     * @param activity the activity to send
     * @param sender the local user sending the activity
     */
    public void sendActivity(String inboxUrl, Map<String, Object> activity, User sender) {
        try {
            String activityJson = objectMapper.writeValueAsString(activity);

            // Generate HTTP signature with all required headers
            // This calculates what the signature SHOULD be, including the host from the URL
            HttpSignatureValidator.SignatureHeaders signatureHeaders = signatureValidator.signRequest(
                HttpMethod.POST.name(),
                inboxUrl,
                activityJson,
                sender.getPrivateKey(),
                baseUrl + "/users/" + sender.getUsername() + "#main-key"
            );

            log.debug("=== HTTP Signature Debug ===");
            log.debug("Inbox URL: {}", inboxUrl);
            log.debug("Expected Host: {}", signatureHeaders.host);
            log.debug("Date: {}", signatureHeaders.date);
            log.debug("Digest: {}", signatureHeaders.digest);
            log.debug("Signature: {}", signatureHeaders.signature);
            log.debug("KeyId: {}", baseUrl + "/users/" + sender.getUsername() + "#main-key");
            log.debug("Activity JSON length: {}", activityJson.length());
            log.debug("===========================");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/activity+json");
            headers.set("Accept", "application/activity+json");

            // CRITICAL: Set the Host header to exactly match what was used in the signature
            // We MUST set this explicitly, otherwise RestTemplate might set it differently
            // (e.g., with port number) and the signature validation will fail
            headers.set("Host", signatureHeaders.host);

            // Set the Date and Digest headers that were used in the signature
            headers.set("Date", signatureHeaders.date);
            headers.set("Digest", signatureHeaders.digest);

            // Set the Signature header
            headers.set("Signature", signatureHeaders.signature);

            HttpEntity<String> entity = new HttpEntity<>(activityJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(inboxUrl, entity, String.class);
            log.info("Sent activity to: {} - Status: {}", inboxUrl, response.getStatusCode());

        } catch (Exception e) {
            log.error("Failed to send activity to: {}", inboxUrl, e);
            throw new RuntimeException("Failed to send activity", e);
        }
    }

    /**
     * Get the inbox URLs of all <em>remote</em> followers of a local user.
     *
     * <p>Local followers are deliberately skipped: they live on the same server and
     * see new activities via the local timeline queries, so there is nothing to
     * federate to them. Without this filter, every call would attempt to
     * {@code fetchRemoteActor(null)} for each local follower row, log a stack trace
     * at ERROR level, and then drop the resulting null from the inbox list.
     *
     * @param userId the local user's ID
     * @return list of remote follower inbox URLs (deduplicated, shared inbox preferred)
     */
    @Transactional(readOnly = true)
    public List<String> getFollowerInboxes(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String actorUri = baseUrl + "/users/" + user.getUsername();
        List<Follow> followers = followRepository.findAcceptedFollowersByActorUri(actorUri);

        return followers.stream()
            .filter(follow -> follow.getRemoteActorUri() != null) // skip local followers (no federation needed)
            .map(follow -> {
                try {
                    RemoteActor actor = remoteActorRepository.findByActorUri(follow.getRemoteActorUri())
                        .orElseGet(() -> fetchRemoteActor(follow.getRemoteActorUri()));
                    return actor.getSharedInboxUrl() != null ? actor.getSharedInboxUrl() : actor.getInboxUrl();
                } catch (Exception e) {
                    log.error("Failed to get inbox for follower: {}", follow.getRemoteActorUri(), e);
                    return null;
                }
            })
            .filter(inbox -> inbox != null)
            .distinct()
            .toList();
    }

    /**
     * Send a Create activity for a new post/object.
     *
     * <p>Like the other federation send methods, this runs on the {@code taskExecutor}
     * thread pool so that user-facing actions (e.g. posting a comment) don't block on
     * federation HTTP delivery. The activity-publish path already wraps this in
     * {@link net.javahippie.fitpub.service.ActivityPostProcessingService#publishToFederationAsync};
     * the additional {@code @Async} here mainly benefits the comment path, which used
     * to call this synchronously on the request thread.
     *
     * @param objectId the ID of the created object
     * @param object the object being created (activity, note, etc.)
     * @param sender the local user creating the object
     * @param toPublic whether to send to public (CC followers)
     */
    @Async("taskExecutor")
    public void sendCreateActivity(String objectId, Map<String, Object> object, User sender, boolean toPublic) {
        try {
            String createId = baseUrl + "/activities/create/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + sender.getUsername();

            Map<String, Object> createActivity = new HashMap<>();
            createActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            createActivity.put("type", "Create");
            createActivity.put("id", createId);
            createActivity.put("actor", actorUri);
            createActivity.put("published", Instant.now().toString());
            createActivity.put("object", object);

            if (toPublic) {
                createActivity.put("to", List.of("https://www.w3.org/ns/activitystreams#Public"));
                createActivity.put("cc", List.of(actorUri + "/followers"));
            } else {
                createActivity.put("to", List.of(actorUri + "/followers"));
            }

            // Send to all follower inboxes
            List<String> inboxes = getFollowerInboxes(sender.getId());
            for (String inbox : inboxes) {
                sendActivity(inbox, createActivity, sender);
            }

            log.info("Sent Create activity for object: {} to {} inboxes", objectId, inboxes.size());

        } catch (Exception e) {
            log.error("Failed to send Create activity for object: {}", objectId, e);
        }
    }

    /**
     * Send a Like activity carrying an emoji reaction in the {@code content} field.
     *
     * <p>This follows the Pleroma/Akkoma convention for emoji reactions: a regular
     * ActivityPub {@code Like} activity with a non-empty {@code content} field whose
     * value is the emoji. Pleroma/Akkoma render this as an emoji reaction; vanilla
     * Mastodon ignores the content and shows it as a regular like — graceful
     * degradation in both directions.
     *
     * <p><strong>Runs on the {@code taskExecutor} thread pool</strong>: federation
     * delivery requires HTTP calls to every follower's inbox (with TLS handshake +
     * RSA signing per delivery). Doing that on the request thread used to add ~1.3 s
     * to a single reaction click on prod. Fire-and-forget here means the user gets
     * their HTTP response as soon as the local DB writes commit; federation runs in
     * the background. Errors are logged in {@link #sendActivity} but never propagated
     * to the caller.
     *
     * @param objectUri the URI of the object being liked
     * @param sender the local user reacting to the object
     * @param emoji the reaction emoji (must be from {@link net.javahippie.fitpub.model.ReactionEmoji#PALETTE})
     */
    @Async("taskExecutor")
    public void sendLikeActivity(String objectUri, User sender, String emoji) {
        try {
            String likeId = baseUrl + "/activities/like/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + sender.getUsername();

            Map<String, Object> likeActivity = new HashMap<>();
            likeActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            likeActivity.put("type", "Like");
            likeActivity.put("id", likeId);
            likeActivity.put("actor", actorUri);
            likeActivity.put("object", objectUri);
            likeActivity.put("content", emoji);
            likeActivity.put("published", Instant.now().toString());

            // Send to all follower inboxes
            List<String> inboxes = getFollowerInboxes(sender.getId());
            for (String inbox : inboxes) {
                sendActivity(inbox, likeActivity, sender);
            }

            log.info("Sent Like activity ({}) for object: {} to {} inboxes", emoji, objectUri, inboxes.size());

        } catch (Exception e) {
            log.error("Failed to send Like activity for object: {}", objectUri, e);
        }
    }

    /**
     * Send Undo Follow activity to remote actor's inbox.
     * This notifies the remote server that we're unfollowing them.
     *
     * <p>Runs on the {@code taskExecutor} pool. The unfollow controller continues
     * with the local follow row deletion regardless of whether this delivery
     * succeeds, so the user's HTTP response shouldn't wait on it.
     *
     * @param remoteActorUri the actor URI being unfollowed
     * @param localUser the local user who is unfollowing
     * @param originalFollowActivityId the ID of the original Follow activity
     */
    @Async("taskExecutor")
    public void sendUndoFollowActivity(String remoteActorUri, User localUser, String originalFollowActivityId) {
        try {
            log.info("Sending Undo Follow activity from {} to {}", localUser.getUsername(), remoteActorUri);

            // 1. Fetch remote actor to get inbox URL
            RemoteActor remoteActor = fetchRemoteActor(remoteActorUri);

            // 2. Reconstruct the original Follow activity
            String actorUri = baseUrl + "/users/" + localUser.getUsername();
            Map<String, Object> followActivity = new HashMap<>();
            followActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            followActivity.put("type", "Follow");
            followActivity.put("id", originalFollowActivityId);
            followActivity.put("actor", actorUri);
            followActivity.put("object", remoteActorUri);

            // 3. Create Undo activity wrapping the Follow
            String undoId = baseUrl + "/activities/undo/" + UUID.randomUUID();
            Map<String, Object> undoActivity = new HashMap<>();
            undoActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            undoActivity.put("type", "Undo");
            undoActivity.put("id", undoId);
            undoActivity.put("actor", actorUri);
            undoActivity.put("object", followActivity);
            undoActivity.put("published", Instant.now().toString());

            // 4. Send to remote actor's inbox (HTTP-signed)
            sendActivity(remoteActor.getInboxUrl(), undoActivity, localUser);

            log.info("Undo Follow activity sent successfully: {} -> {}", localUser.getUsername(), remoteActorUri);

        } catch (Exception e) {
            log.error("Failed to send Undo Follow activity from {} to {}", localUser.getUsername(), remoteActorUri, e);
            // Don't throw exception - we still want to delete the local follow even if federation fails
        }
    }

    /**
     * Send an Undo activity (for unlike, unfollow, etc.).
     *
     * <p>Like {@link #sendLikeActivity}, this is fire-and-forget on the
     * {@code taskExecutor} thread pool: the unlike HTTP response shouldn't wait for
     * federation HTTP calls.
     *
     * @param originalActivityId the ID of the activity being undone
     * @param originalActivity the original activity being undone
     * @param sender the local user undoing the activity
     */
    @Async("taskExecutor")
    public void sendUndoActivity(String originalActivityId, Map<String, Object> originalActivity, User sender) {
        try {
            String undoId = baseUrl + "/activities/undo/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + sender.getUsername();

            Map<String, Object> undoActivity = new HashMap<>();
            undoActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            undoActivity.put("type", "Undo");
            undoActivity.put("id", undoId);
            undoActivity.put("actor", actorUri);
            undoActivity.put("object", originalActivity);
            undoActivity.put("published", Instant.now().toString());

            // Send to all follower inboxes
            List<String> inboxes = getFollowerInboxes(sender.getId());
            for (String inbox : inboxes) {
                sendActivity(inbox, undoActivity, sender);
            }

            log.info("Sent Undo activity for: {} to {} inboxes", originalActivityId, inboxes.size());

        } catch (Exception e) {
            log.error("Failed to send Undo activity for: {}", originalActivityId, e);
        }
    }

    /**
     * Send a Delete activity to notify followers that an object has been deleted.
     *
     * <p>Runs on the {@code taskExecutor} pool. Used for both activity deletes and
     * comment (Note) deletes — the user-facing HTTP response shouldn't wait on the
     * federation fanout to remote follower inboxes.
     *
     * @param objectUri the URI of the deleted object (e.g., activity URI or comment Note URI)
     * @param sender the user who deleted the object
     */
    @Async("taskExecutor")
    public void sendDeleteActivity(String objectUri, User sender) {
        try {
            String deleteId = baseUrl + "/activities/delete/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + sender.getUsername();

            Map<String, Object> deleteActivity = new HashMap<>();
            deleteActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            deleteActivity.put("type", "Delete");
            deleteActivity.put("id", deleteId);
            deleteActivity.put("actor", actorUri);
            deleteActivity.put("object", objectUri);
            deleteActivity.put("published", Instant.now().toString());

            // For delete activities, we typically also send to public if the object was public
            deleteActivity.put("to", List.of("https://www.w3.org/ns/activitystreams#Public"));
            deleteActivity.put("cc", List.of(actorUri + "/followers"));

            // Send to all follower inboxes
            List<String> inboxes = getFollowerInboxes(sender.getId());
            for (String inbox : inboxes) {
                sendActivity(inbox, deleteActivity, sender);
            }

            log.info("Sent Delete activity for: {} to {} inboxes", objectUri, inboxes.size());

        } catch (Exception e) {
            log.error("Failed to send Delete activity for: {}", objectUri, e);
        }
    }

    /**
     * Send a Delete activity for actor (account) deletion.
     * Notifies all followers that this account has been permanently deleted.
     * The actor URI is both the actor and the object being deleted.
     *
     * @param user the user account being deleted
     */
    @Transactional
    public void sendActorDeleteActivity(User user) {
        try {
            String deleteId = baseUrl + "/activities/delete/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + user.getUsername();

            Map<String, Object> deleteActivity = new HashMap<>();
            deleteActivity.put("@context", "https://www.w3.org/ns/activitystreams");
            deleteActivity.put("type", "Delete");
            deleteActivity.put("id", deleteId);
            deleteActivity.put("actor", actorUri);
            deleteActivity.put("object", actorUri); // Actor is the object being deleted
            deleteActivity.put("published", Instant.now().toString());
            deleteActivity.put("to", List.of("https://www.w3.org/ns/activitystreams#Public"));
            deleteActivity.put("cc", List.of(actorUri + "/followers"));

            // Send to all follower inboxes
            List<String> inboxes = getFollowerInboxes(user.getId());
            for (String inbox : inboxes) {
                try {
                    sendActivity(inbox, deleteActivity, user);
                } catch (Exception e) {
                    log.error("Failed to send Delete(Actor) to inbox: {}", inbox, e);
                    // Continue with other inboxes even if one fails
                }
            }

            log.info("Sent Delete(Actor) for: {} to {} inboxes", actorUri, inboxes.size());

        } catch (Exception e) {
            log.error("Failed to send Delete(Actor) for user: {}", user.getUsername(), e);
            // Re-throw to allow caller to handle
            throw new RuntimeException("Failed to send Delete(Actor) activity", e);
        }
    }

    // Helper methods

    private String extractUsername(String actorUri, Map<String, Object> actorData) {
        String preferredUsername = (String) actorData.get("preferredUsername");
        if (preferredUsername != null) {
            return preferredUsername;
        }
        // Fallback: extract from URI
        return actorUri.substring(actorUri.lastIndexOf("/") + 1);
    }

    private String extractSharedInbox(Map<String, Object> actorData) {
        Object endpoints = actorData.get("endpoints");
        if (endpoints instanceof Map) {
            return (String) ((Map<?, ?>) endpoints).get("sharedInbox");
        }
        return null;
    }

    private String extractPublicKey(Map<String, Object> actorData) {
        Object publicKey = actorData.get("publicKey");
        if (publicKey instanceof Map) {
            return (String) ((Map<?, ?>) publicKey).get("publicKeyPem");
        }
        throw new RuntimeException("No public key found in actor data");
    }

    private String extractPublicKeyId(Map<String, Object> actorData) {
        Object publicKey = actorData.get("publicKey");
        if (publicKey instanceof Map) {
            return (String) ((Map<?, ?>) publicKey).get("id");
        }
        return null;
    }

    private String extractAvatarUrl(Map<String, Object> actorData) {
        Object icon = actorData.get("icon");
        if (icon instanceof Map) {
            return (String) ((Map<?, ?>) icon).get("url");
        }
        return null;
    }
}
