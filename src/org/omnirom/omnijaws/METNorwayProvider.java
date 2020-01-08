// Author this class - vladrevers

package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import org.apache.http.client.HttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.util.TimeZone;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

public class METNorwayProvider extends AbstractWeatherProvider {
    private static final String TAG = "METNorwayProvider";

    private static final String URL_WEATHER =
            "https://api.met.no/weatherapi/locationforecast/2.0/?";
    private static final String PART_COORDINATES =
            "lat=%f&lon=%f";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&username=omnijaws&isNameRequired=true";

    public static final SimpleDateFormat gmt0Format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    public static final SimpleDateFormat userTimeZoneFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    public static final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public METNorwayProvider(Context context) {
        super(context);
        initTimeZoneFormat();
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES, location.getLatitude(), location.getLongitude());
        return getAllWeather(coordinates, metric);
    }

    public List<WeatherInfo.WeatherLocation> getLocations(String input) {
        String lang = Locale.getDefault().getLanguage().replaceFirst("_", "-");
        String url = String.format(URL_PLACES, Uri.encode(input), lang);
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONArray jsonResults = new JSONObject(response).getJSONArray("geonames");
            ArrayList<WeatherInfo.WeatherLocation> results = new ArrayList<>(jsonResults.length());
            int count = jsonResults.length();

            for (int i = 0; i < count; i++) {
                JSONObject result = jsonResults.getJSONObject(i);
                WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();

                String city = result.getString("name");
                String area = result.getString("adminName1");

                location.id = String.format(Locale.US, PART_COORDINATES, result.getDouble("lat"), result.getDouble("lng"));
                location.city = city;
                location.countryId = city.equals(area) ? result.getString("countryName") : result.getString("countryName") + ", " + area;
                results.add(location);
            }

            return results;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return getAllWeather(id, metric);
    }

    private WeatherInfo getAllWeather(String coordinates, boolean metric)
    {
        String url = URL_WEATHER + coordinates;
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONArray forecasts = new JSONObject(response).getJSONArray("forecast");
            JSONObject weather = forecasts.getJSONObject(0).getJSONObject("data");

            double windSpeed = weather.getDouble("wind_speed");
            if (metric) {
                windSpeed *= 3.6;
            }

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ coordinates,
                    /* cityId */ getNameLocality(coordinates),
                    /* condition */ mapWeatherIconToDescription(weather.getJSONObject("last_1_hours").getInt("weather_symbol")),
                    /* conditionCode */ mapWeatherIconToCode(weather.getJSONObject("last_1_hours").getInt("weather_symbol")),
                    /* temperature */ convertTemperature(weather.getDouble("air_temperature"), metric),
                    /* humidity */ (float) weather.getDouble("relative_humidity"),
                    /* wind */ (float) windSpeed,
                    /* windDir */ (int) weather.getDouble("wind_from_direction"),
                    metric,
                    parseForecasts(forecasts, metric),
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (coordinates = " + coordinates + ")", e);
        }

        return null;
    }

    private ArrayList<DayForecast> parseForecasts(JSONArray forecasts, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>(5);
        int count = forecasts.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }

        int startIndex = 0;

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        String yesterday = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());

        while (convertTimeZone(forecasts.getJSONObject(startIndex).getString("time")).contains(yesterday)) {
            startIndex++;
        }

        boolean endDay = (startIndex != 0) ? false : isEndDay(convertTimeZone(forecasts.getJSONObject(startIndex).getString("time")));

        for (int i = 0; i < 5; i++) {
            DayForecast item;
            try {

                double temp_max = Double.MIN_VALUE;
                double temp_min = Double.MAX_VALUE;
                String day = getDay(i);
                int weatherCode = 0;

                for (int j = startIndex; convertTimeZone(forecasts.getJSONObject(j).getString("time")).contains(day); j++) {
                    startIndex++;
                    double tempI = forecasts.getJSONObject(j).getJSONObject("data").getDouble("air_temperature");

                    if (tempI > temp_max) {
                        temp_max = tempI;
                    }
                    if (tempI < temp_min) {
                        temp_min = tempI;
                    }

                    boolean has1Hours = forecasts.getJSONObject(j).getJSONObject("data").has("last_1_hours");

                    if ((i == 0 && endDay) || isMorningOrAfternoon(convertTimeZone(forecasts.getJSONObject(j).getString("time")), has1Hours)) {
                        int stepWeatherCode;

                        if(has1Hours) {
                            stepWeatherCode = forecasts.getJSONObject(j).getJSONObject("data").getJSONObject("last_1_hours").getInt("weather_symbol");
                        } else {
                            stepWeatherCode = forecasts.getJSONObject(j).getJSONObject("data").getJSONObject("last_6_hours").getInt("weather_symbol");
                        }

                        if (stepWeatherCode > weatherCode) {
                            weatherCode = stepWeatherCode;
                        }
                    }
                }
                item = new DayForecast(
                        /* low */ convertTemperature(temp_min, metric),
                        /* high */ convertTemperature(temp_max, metric),
                        /* condition */ mapWeatherIconToDescription(weatherCode),
                        /* conditionCode */ mapWeatherIconToCode(weatherCode),
                        day,
                        metric);
            } catch (JSONException e) {
                Log.w(TAG, "Invalid forecast for day " + i + " creating dummy", e);
                item = new DayForecast(
                        /* low */ 0,
                        /* high */ 0,
                        /* condition */ "",
                        /* conditionCode */ -1,
                        "NaN",
                        metric);
            }
            result.add(item);
        }
        // clients assume there are 5  entries - so fill with dummy if needed
        if (result.size() < 5) {
            for (int i = result.size(); i < 5; i++) {
                Log.w(TAG, "Missing forecast for day " + i + " creating dummy");
                DayForecast item = new DayForecast(
                        /* low */ 0,
                        /* high */ 0,
                        /* condition */ "",
                        /* conditionCode */ -1,
                        "NaN",
                        metric);
                result.add(item);
            }
        }

        return result;
    }

    private static String mapWeatherIconToDescription(int weather_symbol) {
        String prefixed = "";
        if(weather_symbol > 100) {
            weather_symbol -= 100;
            prefixed = "Dark_";
        }

        String result = "Unknown";

        switch (weather_symbol) {
            case 1:
                result = "Sun";
                break;
            case 2:
                result = "LightCloud";
                break;
            case 3:
                result = "PartlyCloud";
                break;
            case 4:
                result = "Cloud";
                break;
            case 5:
                result = "LightRainSun";
                break;
            case 6:
                result = "LightRainThunderSun";
                break;
            case 7:
                result = "SleetSun";
                break;
            case 8:
                result = "SnowSun";
                break;
            case 9:
                result = "LightRain";
                break;
            case 10:
                result = "Rain";
                break;
            case 11:
                result = "RainThunder";
                break;
            case 12:
                result = "Sleet";
                break;
            case 13:
                result = "Snow";
                break;
            case 14:
                result = "SnowThunder";
                break;
            case 15:
                result = "Fog";
                break;
            case 20:
                result = "SleetSunThunder";
                break;
            case 21:
                result = "SnowSunThunder";
                break;
            case 22:
                result = "LightRainThunder";
                break;
            case 23:
                result = "SleetThunder";
                break;
            case 24:
                result = "DrizzleThunderSun";
                break;
            case 25:
                result = "RainThunderSun";
                break;
            case 26:
                result = "LightSleetThunderSun";
                break;
            case 27:
                result = "HeavySleetThunderSun";
                break;
            case 28:
                result = "LightSnowThunderSun";
                break;
            case 29:
                result = "HeavySnowThunderSun";
                break;
            case 30:
                result = "DrizzleThunder";
                break;
            case 31:
                result = "LightSleetThunder";
                break;
            case 32:
                result = "HeavySleetThunder";
                break;
            case 33:
                result = "LightSnowThunder";
                break;
            case 34:
                result = "HeavySnowThunder";
                break;
            case 40:
                result = "DrizzleSun";
                break;
            case 41:
                result = "RainSun";
                break;
            case 42:
                result = "LightSleetSun";
                break;
            case 43:
                result = "HeavySleetSun";
                break;
            case 44:
                result = "LightSnowSun";
                break;
            case 45:
                result = "HeavysnowSun";
                break;
            case 46:
                result = "Drizzle";
                break;
            case 47:
                result = "LightSleet";
                break;
            case 48:
                result = "HeavySleet";
                break;
            case 49:
                result = "LightSnow";
                break;
            case 50:
                result = "HeavySnow";
                break;
        }

        return prefixed + result;
    }

    /* Thanks Chronus(app) */
    private static int mapWeatherIconToCode(int weather_symbol) {
        if(weather_symbol > 100)
            weather_symbol -= 100;

        switch (weather_symbol) {
            case 1:
                return 32;
            case 2:
                return 34;
            case 3:
                return 30;
            case 4:
                return 26;
            case 5:
            case 40:
            case 41:
                return 40;
            case 6:
            case 24:
            case 25:
                return 39;
            case 7:
            case 23:
            case 31:
            case 32:
            case 42:
            case 43:
                return 6;
            case 8:
            case 44:
            case 45:
                return 14;
            case 9:
            case 46:
                return 11;
            case 10:
                return 12;
            case 11:
            case 22:
            case 30:
                return 4;
            case 12:
            case 47:
            case 48:
                return 18;
            case 13:
            case 49:
            case 50:
                return 16;
            case 14:
            case 33:
            case 34:
                return 15;
            case 15:
                return 20;
            case 20:
            case 21:
            case 26:
            case 27:
            case 28:
            case 29:
                return 42;
            default:
                return -1;
        }
    }

    @Override
    protected String retrieve(String url) {
        HttpGet request = new HttpGet(url);
        try {
            HttpClient client = new DefaultHttpClient();
            client.getParams().setParameter(CoreProtocolPNames.USER_AGENT, "OmniJawsApp/1.0");
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (!(code == HttpStatus.SC_OK || code == HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION)) {
                log(TAG, "HttpStatus: " + code + " for url: " + url);
                return null;
            }
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return EntityUtils.toString(entity);
            }
        } catch (IOException e) {
            Log.e(TAG, "Couldn't retrieve data from url " + url, e);
        }
        return null;
    }

    private void initTimeZoneFormat() {
        gmt0Format.setTimeZone(TimeZone.getTimeZone("GMT"));
        userTimeZoneFormat.setTimeZone(TimeZone.getDefault());
    }

    private String convertTimeZone(String tmp) {
        try {
            return userTimeZoneFormat.format(gmt0Format.parse(tmp));
        } catch (ParseException e) {
            return tmp;
        }
    }


    private String getDay(int i) {
        Calendar calendar = Calendar.getInstance();
        if(i > 0) {
            calendar.add(Calendar.DATE, i);
        }
        return dayFormat.format(calendar.getTime());
    }

    private Boolean isMorningOrAfternoon(String time, boolean has1Hours) {
        int startI = has1Hours ? 6 : 12;
        for (int i = startI; i <= 18; i++) {
            if(time.contains("T" + i)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEndDay(String time) {
        for (int i = 19; i <= 23; i++) {
            if(time.contains("T" + i)) {
                return true;
            }
        }
        return false;
    }

    private String getNameLocality(String coordinate) {
        double latitude = Double.valueOf(coordinate.substring(4, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.indexOf("lon=") + 4));
        Geocoder geocoder = new Geocoder(mContext.getApplicationContext(), Locale.getDefault());
        try {
            List<Address> listAddresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(listAddresses != null && listAddresses.size() > 0){
                return listAddresses.get(0).getLocality();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "Unknown";
    }

    private static float convertTemperature(double value, boolean metric) {
        if (!metric) {
            value = (value * 1.8) + 32;
        }
        return (float) value;
    }

    public boolean shouldRetry() {
        return false;
    }
}
