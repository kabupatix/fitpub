package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.TrackPoint;
import net.javahippie.fitpub.model.entity.WeatherData;
import net.javahippie.fitpub.repository.WeatherDataRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for fetching and managing weather data for activities.
 * Uses Open-Meteo archive API to retrieve historical weather data.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherDataRepository weatherDataRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${fitpub.weather.enabled:false}")
    private boolean weatherEnabled;

    private static final String OPEN_METEO_API_URL = "https://archive-api.open-meteo.com/v1/archive?latitude={latitude}&longitude={longitude}&start_date={start_date}&end_date={end_date}&hourly={hourly}";

    /**
     * Fetch and store weather data for an activity.
     * Uses the activity's start location and timestamp to get weather conditions.
     *
     * @param activity the activity
     * @return the weather data, or empty if fetching failed
     */
    @Transactional
    public Optional<WeatherData> fetchWeatherForActivity(Activity activity) {
        log.info("=== Weather fetch requested for activity {} ===", activity.getId());

        if (activity.getTrackPointsJson() == null || activity.getTrackPointsJson().isEmpty()) {
            log.warn("No track points available for activity {} - cannot fetch weather", activity.getId());
            return Optional.empty();
        }

        log.debug("Track points JSON length: {} chars", activity.getTrackPointsJson().length());

        try {
            Optional<TrackPoint> trackPoint = activity.findFirstTrackpoint();

            if (trackPoint.isEmpty()) {
                return Optional.empty();
            } else {
                var resolvedTrackPoint = trackPoint.get();
                WeatherData weatherData = fetchCurrentWeather(resolvedTrackPoint.lat(), resolvedTrackPoint.lon(), activity.getId(), activity.getStartedAt());

                if (weatherData != null) {
                    log.info("Successfully fetched and parsed weather data. Attempting to save to database...");
                    try {
                        WeatherData saved = weatherDataRepository.save(weatherData);
                        log.info("Weather data SUCCESSFULLY SAVED to database with ID: {}", saved.getId());
                        return Optional.of(saved);
                    } catch (Exception e) {
                        log.error("FAILED to save weather data to database: {}", e.getMessage(), e);
                        return Optional.empty();
                    }
                } else {
                    log.error("Weather data fetch returned NULL - check API errors above");
                }

            }
        } catch (Exception e) {
            log.error("EXCEPTION while fetching weather data for activity {}: {}",
                    activity.getId(), e.getMessage(), e);
        }

        return Optional.empty();
    }

    /**
     * Fetch current weather data from Open-Meteo archive API.
     */
    private WeatherData fetchCurrentWeather(double lat, double lon, UUID activityId, LocalDateTime startedAt) {
        log.info("=== fetchCurrentWeather START === activityId={}, lat={}, lon={}", activityId, lat, lon);
        try {

            Map<String, Object> uriVariables = Map.of(
                    "latitude", lat,
                    "longitude", lon,
                    "start_date", startedAt.format(DateTimeFormatter.ISO_DATE),
                    "end_date", startedAt.format(DateTimeFormatter.ISO_DATE),
                    "hourly", "temperature_2m,apparent_temperature,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_direction_10m,cloud_cover,rain,snowfall,precipitation,visibility,weather_code"
            );

            log.info("Request parameters: lat={}, lon={}, date={}", lat, lon, startedAt);

            long startTime = System.currentTimeMillis();
            log.info("Sending HTTP GET request to Open-Meteo...");
            String response = restTemplate.getForObject(OPEN_METEO_API_URL, String.class, uriVariables);
            long duration = System.currentTimeMillis() - startTime;

            log.info("HTTP request completed in {}ms, response received", duration);

            if (response == null) {
                log.error("API response is NULL - RestTemplate returned null, no data from Open-Meteo");
                return null;
            }

            log.info("API response (first 300 chars): {}",
                    response.length() > 300 ? response.substring(0, 300) + "..." : response);

            log.info("Parsing weather response JSON...");
            WeatherData weatherData = parseWeatherResponse(response, activityId, startedAt);

            if (weatherData == null) {
                log.error("FAILED to parse weather response - see parsing errors above");
            } else {
                log.info("Successfully parsed weather data: temp={}°C, condition='{}', wind={} m/s, precipitation={} mm",
                        weatherData.getTemperatureCelsius(),
                        weatherData.getWeatherCondition(),
                        weatherData.getWindSpeedMps(),
                        weatherData.getPrecipitationMm());
            }

            log.info("=== fetchCurrentWeather END === success={}", (weatherData != null));
            return weatherData;

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("HTTP client error (4xx) from Open-Meteo API: {} - {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            return null;
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("HTTP server error (5xx) from Open-Meteo API: {} - {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            return null;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("Network error accessing Open-Meteo API: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected exception fetching weather for activity {}: {}", activityId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse Open-Meteo archive API response and create WeatherData entity.
     * Extracts the hourly data point matching the activity's start hour.
     */
    private WeatherData parseWeatherResponse(String response, UUID activityId, LocalDateTime startedAt) {
        log.debug("=== parseWeatherResponse START === activityId={}", activityId);
        try {
            JsonNode root = objectMapper.readTree(response);

            JsonNode hourly = root.get("hourly");
            if (hourly == null) {
                log.warn("Response JSON does not contain 'hourly' section");
                return null;
            }

            // Find the index matching the activity start hour
            JsonNode times = hourly.get("time");
            String targetHour = startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"));
            int hourIndex = -1;
            for (int i = 0; i < times.size(); i++) {
                if (times.get(i).asText().equals(targetHour)) {
                    hourIndex = i;
                    break;
                }
            }

            if (hourIndex == -1) {
                log.warn("No matching hour found for {} in response", targetHour);
                return null;
            }

            log.debug("Matched hour index {} for {}", hourIndex, targetHour);

            WeatherData weatherData = new WeatherData();
            weatherData.setActivityId(activityId);
            weatherData.setTemperatureCelsius(getHourlyBigDecimal(hourly, "temperature_2m", hourIndex));
            weatherData.setFeelsLikeCelsius(getHourlyBigDecimal(hourly, "apparent_temperature", hourIndex));
            weatherData.setHumidity(getHourlyInteger(hourly, "relative_humidity_2m", hourIndex));
            weatherData.setPressure(getHourlyInteger(hourly, "surface_pressure", hourIndex));
            weatherData.setWindDirection(getHourlyInteger(hourly, "wind_direction_10m", hourIndex));
            weatherData.setCloudiness(getHourlyInteger(hourly, "cloud_cover", hourIndex));
            weatherData.setVisibilityMeters(getHourlyInteger(hourly, "visibility", hourIndex));
            weatherData.setPrecipitationMm(getHourlyBigDecimal(hourly, "precipitation", hourIndex));
            weatherData.setWeatherCondition(mapWmoCodeToCondition(getHourlyInteger(hourly, "weather_code", hourIndex)));
            weatherData.setWeatherDescription(mapWmoCodeToDescription(getHourlyInteger(hourly, "weather_code", hourIndex)));

            // Open-Meteo returns wind speed in km/h, convert to m/s
            BigDecimal windKmh = getHourlyBigDecimal(hourly, "wind_speed_10m", hourIndex);
            if (windKmh != null) {
                weatherData.setWindSpeedMps(windKmh.divide(BigDecimal.valueOf(3.6), 2, RoundingMode.HALF_UP));
            }

            // Open-Meteo returns snowfall in cm, convert to mm
            BigDecimal snowCm = getHourlyBigDecimal(hourly, "snowfall", hourIndex);
            if (snowCm != null) {
                weatherData.setSnowMm(snowCm.multiply(BigDecimal.TEN));
            }

            weatherData.setFetchedAt(LocalDateTime.now());
            weatherData.setDataSource("open-meteo");

            log.info("Successfully parsed weather data: temp={}°C, condition='{}', wind={} m/s",
                    weatherData.getTemperatureCelsius(), weatherData.getWeatherCondition(), weatherData.getWindSpeedMps());
            return weatherData;

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to parse weather response as JSON: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error parsing weather response: {}", e.getMessage(), e);
            return null;
        }
    }

    private BigDecimal getHourlyBigDecimal(JsonNode hourly, String field, int index) {
        JsonNode array = hourly.get(field);
        if (array != null && index < array.size() && !array.get(index).isNull()) {
            return BigDecimal.valueOf(array.get(index).asDouble());
        }
        return null;
    }

    private Integer getHourlyInteger(JsonNode hourly, String field, int index) {
        JsonNode array = hourly.get(field);
        if (array != null && index < array.size() && !array.get(index).isNull()) {
            return array.get(index).asInt();
        }
        return null;
    }

    /**
     * Map WMO weather code to a condition string.
     * See https://open-meteo.com/en/docs#weathervariables
     */
    private String mapWmoCodeToCondition(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Clear";
            case 1, 2, 3 -> "Clouds";
            case 45, 48 -> "Fog";
            case 51, 53, 55 -> "Drizzle";
            case 56, 57 -> "Drizzle";
            case 61, 63, 65 -> "Rain";
            case 66, 67 -> "Rain";
            case 71, 73, 75, 77 -> "Snow";
            case 80, 81, 82 -> "Rain";
            case 85, 86 -> "Snow";
            case 95, 96, 99 -> "Thunderstorm";
            default -> "Unknown";
        };
    }

    /**
     * Map WMO weather code to a human-readable description.
     */
    private String mapWmoCodeToDescription(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "clear sky";
            case 1 -> "mainly clear";
            case 2 -> "partly cloudy";
            case 3 -> "overcast";
            case 45 -> "fog";
            case 48 -> "depositing rime fog";
            case 51 -> "light drizzle";
            case 53 -> "moderate drizzle";
            case 55 -> "dense drizzle";
            case 56 -> "light freezing drizzle";
            case 57 -> "dense freezing drizzle";
            case 61 -> "slight rain";
            case 63 -> "moderate rain";
            case 65 -> "heavy rain";
            case 66 -> "light freezing rain";
            case 67 -> "heavy freezing rain";
            case 71 -> "slight snow fall";
            case 73 -> "moderate snow fall";
            case 75 -> "heavy snow fall";
            case 77 -> "snow grains";
            case 80 -> "slight rain showers";
            case 81 -> "moderate rain showers";
            case 82 -> "violent rain showers";
            case 85 -> "slight snow showers";
            case 86 -> "heavy snow showers";
            case 95 -> "thunderstorm";
            case 96 -> "thunderstorm with slight hail";
            case 99 -> "thunderstorm with heavy hail";
            default -> "unknown";
        };
    }

    /**
     * Get weather data for an activity.
     *
     * @param activityId the activity ID
     * @return optional weather data
     */
    public Optional<WeatherData> getWeatherForActivity(UUID activityId) {
        return weatherDataRepository.findByActivityId(activityId);
    }

    /**
     * Delete weather data for an activity.
     *
     * @param activityId the activity ID
     */
    @Transactional
    public void deleteWeatherForActivity(UUID activityId) {
        weatherDataRepository.deleteByActivityId(activityId);
    }

}
