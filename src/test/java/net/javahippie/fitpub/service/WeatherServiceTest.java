package net.javahippie.fitpub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.WeatherData;
import net.javahippie.fitpub.repository.WeatherDataRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WeatherService.
 */
@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    @Mock
    private WeatherDataRepository weatherDataRepository;

    @Mock
    private RestTemplate restTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private WeatherService weatherService;

    private Activity testActivity;
    private UUID activityId;

    // Sample Open-Meteo archive API response (clear sky, WMO code 0)
    private static final String SAMPLE_WEATHER_RESPONSE = """
        {
          "latitude": 49.98,
          "longitude": 8.26,
          "hourly": {
            "time": ["2025-11-23T17:00", "2025-11-23T18:00", "2025-11-23T19:00"],
            "temperature_2m": [14.0, 15.5, 13.8],
            "apparent_temperature": [12.5, 14.2, 12.0],
            "relative_humidity_2m": [60, 65, 70],
            "surface_pressure": [1012, 1013, 1012],
            "wind_speed_10m": [10.0, 12.6, 11.0],
            "wind_direction_10m": [170, 180, 190],
            "cloud_cover": [15, 20, 25],
            "rain": [0.0, 0.0, 0.0],
            "snowfall": [0.0, 0.0, 0.0],
            "precipitation": [0.0, 0.0, 0.0],
            "visibility": [10000, 10000, 10000],
            "weather_code": [0, 0, 1]
          }
        }
        """;

    @BeforeEach
    void setUp() {
        activityId = UUID.randomUUID();
        testActivity = new Activity();
        testActivity.setId(activityId);
        testActivity.setStartedAt(LocalDateTime.of(2025, 11, 23, 18, 8, 9));

        // Inject the real RestTemplate mock and set config values
        ReflectionTestUtils.setField(weatherService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(weatherService, "weatherEnabled", true);
    }

    @Test
    @DisplayName("Should successfully fetch weather with SHORT field names (lat/lon)")
    void testFetchWeather_ShortFieldNames() {
        // Track points with SHORT field names
        String trackPointsJson = """
            [
              {
                "timestamp": "2025-11-23T18:08:09",
                "lat": 49.98939173296094,
                "lon": 8.255225038155913,
                "elevation": 100.5,
                "heartRate": 116
              }
            ]
            """;
        testActivity.setTrackPointsJson(trackPointsJson);

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenReturn(SAMPLE_WEATHER_RESPONSE);
        when(weatherDataRepository.save(any(WeatherData.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isPresent());
        WeatherData weatherData = result.get();
        assertEquals(activityId, weatherData.getActivityId());
        assertEquals(new BigDecimal("15.5"), weatherData.getTemperatureCelsius());
        assertEquals("Clear", weatherData.getWeatherCondition());

        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class), any(Map.class));
        verify(weatherDataRepository, times(1)).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should successfully fetch weather with LONG field names (latitude/longitude)")
    void testFetchWeather_LongFieldNames() {
        // Track points with LONG field names (as used in production)
        String trackPointsJson = """
            [
              {
                "timestamp": "2025-11-23T18:08:09",
                "latitude": 49.98939173296094,
                "longitude": 8.255225038155913,
                "elevation": null,
                "heartRate": 116,
                "cadence": null,
                "power": null,
                "speed": null,
                "temperature": null,
                "distance": null
              }
            ]
            """;
        testActivity.setTrackPointsJson(trackPointsJson);

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenReturn(SAMPLE_WEATHER_RESPONSE);
        when(weatherDataRepository.save(any(WeatherData.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isPresent());
        WeatherData weatherData = result.get();
        assertEquals(activityId, weatherData.getActivityId());
        assertEquals(new BigDecimal("15.5"), weatherData.getTemperatureCelsius());
        assertEquals(new BigDecimal("14.2"), weatherData.getFeelsLikeCelsius());
        assertEquals(65, weatherData.getHumidity());
        assertEquals(1013, weatherData.getPressure());
        assertEquals(new BigDecimal("3.50"), weatherData.getWindSpeedMps());
        assertEquals("clear sky", weatherData.getWeatherDescription());

        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class), any(Map.class));
        verify(weatherDataRepository, times(1)).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should return empty when track points JSON is null")
    void testFetchWeather_NoTrackPoints() {
        testActivity.setTrackPointsJson(null);

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should return empty when track points JSON is empty")
    void testFetchWeather_EmptyTrackPoints() {
        testActivity.setTrackPointsJson("");

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should return empty when track points array is empty")
    void testFetchWeather_EmptyArray() {
        testActivity.setTrackPointsJson("[]");

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should return empty when track points missing lat/lon fields")
    void testFetchWeather_MissingCoordinates() {
        String trackPointsJson = """
            [
              {
                "timestamp": "2025-11-23T18:08:09",
                "elevation": 100.5,
                "heartRate": 116
              }
            ]
            """;
        testActivity.setTrackPointsJson(trackPointsJson);

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
        verify(restTemplate, never()).getForObject(anyString(), eq(String.class), any(Map.class));
    }

    @Test
    @DisplayName("Should handle 401 authentication error from API")
    void testFetchWeather_AuthenticationError() {
        testActivity.setTrackPointsJson("[{\"latitude\":50.0,\"longitude\":8.0}]");

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenThrow(new HttpClientErrorException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "{\"error\":true,\"reason\":\"Unauthorized\"}".getBytes(),
                null
            ));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should handle network/connection errors")
    void testFetchWeather_NetworkError() {
        testActivity.setTrackPointsJson("[{\"latitude\":50.0,\"longitude\":8.0}]");

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenThrow(new ResourceAccessException("Connection timeout"));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should handle malformed JSON response from API")
    void testFetchWeather_MalformedResponse() {
        testActivity.setTrackPointsJson("[{\"latitude\":50.0,\"longitude\":8.0}]");

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenReturn("this is not valid JSON");

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isEmpty());
        verify(weatherDataRepository, never()).save(any(WeatherData.class));
    }

    @Test
    @DisplayName("Should parse all weather fields correctly")
    void testParseWeatherResponse_AllFields() {
        String responseWithRain = """
            {
              "latitude": 50.0,
              "longitude": 8.0,
              "hourly": {
                "time": ["2025-11-23T17:00", "2025-11-23T18:00", "2025-11-23T19:00"],
                "temperature_2m": [11.0, 10.0, 9.5],
                "apparent_temperature": [9.0, 8.5, 8.0],
                "relative_humidity_2m": [75, 80, 85],
                "surface_pressure": [1014, 1015, 1015],
                "wind_speed_10m": [18.0, 19.8, 20.0],
                "wind_direction_10m": [260, 270, 275],
                "cloud_cover": [70, 75, 80],
                "rain": [1.5, 2.5, 3.0],
                "snowfall": [0.0, 0.0, 0.0],
                "precipitation": [1.5, 2.5, 3.0],
                "visibility": [9000, 8000, 7000],
                "weather_code": [61, 61, 63]
              }
            }
            """;

        testActivity.setTrackPointsJson("[{\"latitude\":50.0,\"longitude\":8.0}]");

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenReturn(responseWithRain);
        when(weatherDataRepository.save(any(WeatherData.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isPresent());
        WeatherData weatherData = result.get();
        assertEquals(new BigDecimal("10.0"), weatherData.getTemperatureCelsius());
        assertEquals(new BigDecimal("8.5"), weatherData.getFeelsLikeCelsius());
        assertEquals("Rain", weatherData.getWeatherCondition());
        assertEquals("slight rain", weatherData.getWeatherDescription());
        assertEquals(new BigDecimal("5.50"), weatherData.getWindSpeedMps());
        assertEquals(270, weatherData.getWindDirection());
        assertEquals(75, weatherData.getCloudiness());
        assertEquals(8000, weatherData.getVisibilityMeters());
        assertEquals(new BigDecimal("2.5"), weatherData.getPrecipitationMm());
        assertEquals("open-meteo", weatherData.getDataSource());
        assertNotNull(weatherData.getFetchedAt());
    }

    @Test
    @DisplayName("Should handle response with missing optional fields")
    void testParseWeatherResponse_MinimalFields() {
        String minimalResponse = """
            {
              "latitude": 50.0,
              "longitude": 8.0,
              "hourly": {
                "time": ["2025-11-23T18:00"],
                "temperature_2m": [15.0],
                "apparent_temperature": [14.0],
                "relative_humidity_2m": [60],
                "surface_pressure": [1010],
                "wind_speed_10m": [null],
                "wind_direction_10m": [null],
                "cloud_cover": [null],
                "rain": [null],
                "snowfall": [null],
                "precipitation": [null],
                "visibility": [null],
                "weather_code": [2]
              }
            }
            """;

        testActivity.setTrackPointsJson("[{\"latitude\":50.0,\"longitude\":8.0}]");

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenReturn(minimalResponse);
        when(weatherDataRepository.save(any(WeatherData.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isPresent());
        WeatherData weatherData = result.get();
        assertEquals(new BigDecimal("15.0"), weatherData.getTemperatureCelsius());
        assertEquals("Clouds", weatherData.getWeatherCondition());
        assertNull(weatherData.getWindSpeedMps());
        assertNull(weatherData.getPrecipitationMm());
    }

    @Test
    @DisplayName("Should get existing weather data by activity ID")
    void testGetWeatherForActivity() {
        WeatherData weatherData = new WeatherData();
        weatherData.setActivityId(activityId);

        when(weatherDataRepository.findByActivityId(activityId)).thenReturn(Optional.of(weatherData));

        Optional<WeatherData> result = weatherService.getWeatherForActivity(activityId);

        assertTrue(result.isPresent());
        assertEquals(activityId, result.get().getActivityId());
    }

    @Test
    @DisplayName("Should delete weather data by activity ID")
    void testDeleteWeatherForActivity() {
        doNothing().when(weatherDataRepository).deleteByActivityId(activityId);

        weatherService.deleteWeatherForActivity(activityId);

        verify(weatherDataRepository, times(1)).deleteByActivityId(activityId);
    }

    @Test
    @DisplayName("Should handle mixed field names (lat + longitude)")
    void testFetchWeather_MixedFieldNames() {
        // Edge case: one coordinate uses short form, other uses long form
        String trackPointsJson = """
            [
              {
                "timestamp": "2025-11-23T18:08:09",
                "lat": 49.98939173296094,
                "longitude": 8.255225038155913,
                "elevation": 100.5
              }
            ]
            """;
        testActivity.setTrackPointsJson(trackPointsJson);

        when(restTemplate.getForObject(anyString(), eq(String.class), any(Map.class)))
            .thenReturn(SAMPLE_WEATHER_RESPONSE);
        when(weatherDataRepository.save(any(WeatherData.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<WeatherData> result = weatherService.fetchWeatherForActivity(testActivity);

        assertTrue(result.isPresent());
        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class), any(Map.class));
    }
}
