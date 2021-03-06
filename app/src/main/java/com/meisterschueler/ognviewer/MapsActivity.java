package com.meisterschueler.ognviewer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.ui.IconGenerator;
import com.meisterschueler.ognviewer.common.AppConstants;
import com.meisterschueler.ognviewer.common.ReceiverBundle;
import com.meisterschueler.ognviewer.common.Utils;

import org.ogn.commons.beacon.AircraftBeacon;
import org.ogn.commons.beacon.AircraftDescriptor;
import org.ogn.commons.beacon.AircraftType;
import org.ogn.commons.beacon.ReceiverBeacon;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import timber.log.Timber;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback,
        GoogleMap.OnCameraMoveStartedListener, GoogleMap.OnCameraIdleListener, GoogleMap.OnMapLoadedCallback {

    private static final String TAG = "MapsActivity";

    private OgnService ognService;
    private Circle rangeCircle;
    private BroadcastReceiver aircraftReceiver;
    private BroadcastReceiver receiverReceiver;
    private BroadcastReceiver actionReceiver;
    private Map<String, Marker> aircraftMarkerMap = new HashMap<>();
    private Map<String, Marker> receiverMarkerMap = new HashMap<>();
    private GoogleMap mMap; // Might be null if Google Play services APK is not available.
    private boolean ognServiceConnected = false;
    private boolean mapLoaded = false;
    private CountDownTimer emptyFilterTimer;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            OgnService.LocalBinder localBinder = (OgnService.LocalBinder) binder;
            ognService = localBinder.getService();
            ognServiceConnected = true;
            changeAircraftTimeout(); // important to do that after service connected!
            changeTCPServerState();
            updateKnownMarkers();
        }

        public void onServiceDisconnected(ComponentName className) {
            //this only happens when something goes wrong
            //it does not happen when activity is paused or destroyed
            ognService = null;
            ognServiceConnected = false;
        }
    };

    private void updateKnownAircrafts(final Map<String, AircraftBundle> aircraftMap) {
        for (final AircraftBundle bundle : aircraftMap.values()) {
            updateAircraftBeaconMarker(bundle);
        }
    }

    private void updateKnownReceivers(final Map<String, ReceiverBundle> receiverMap) {
        for (final ReceiverBundle bundle : receiverMap.values()) {
            updateReceiverBeaconMarker(bundle);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        final int id = item.getItemId();

        switch (id) {
            case R.id.action_settings:
                Intent i = new Intent(this, PrefsActivity.class);
                startActivityForResult(i, AppConstants.ACTIVITY_REQUEST_CODE_SETTINGS);
                break;
            case R.id.action_manageids:
                Intent i2 = new Intent(this, ManageIDsActivity.class);
                startActivity(i2);
                break;
            case R.id.action_currentlocation:
                zoomToCurrentLocation();
                break;

            case R.id.action_about:
                String versionName = "";
                try {
                    versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }

                AlertDialog alertDialog = new AlertDialog.Builder(MapsActivity.this).create();
                alertDialog.setTitle("About");
                alertDialog.setMessage("OGN Viewer " + versionName + "\nby\nKonstantin Gründger\nDominik Prikril");
                alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // User pressed OK button.
                    }
                });
                alertDialog.show();
                break;
            case R.id.action_exit:
                stopService(new Intent(getBaseContext(), OgnService.class));
                finish();
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == AppConstants.ACTIVITY_REQUEST_CODE_SETTINGS)
        {
            //aprs filter
            //String message = data.getStringExtra("MESSAGE"); //leave this for future usage
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String aprsFilter = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");
            updateAprsFilterRange(aprsFilter);

            //map type
            changeMapType();

            changeKeepScreenOn();
            changeAircraftTimeout(); // WARNING: will not work here, because ognService connects async

            //receivers
            Boolean showreceivers = sharedPreferences.getBoolean(getString(R.string.key_showreceivers_preference), true);
            // receiverMarkerMap should always be empty with current implementation 2018-02-26
            for (Marker m : receiverMarkerMap.values()) { // this is not slow!
                m.setVisible(showreceivers);
            }
            Timber.d("applied changed options");
        }
    }

    void zoomToCurrentLocation() {
        final String coarseLocationPermissionString = Manifest.permission.ACCESS_COARSE_LOCATION;

        if (ContextCompat.checkSelfPermission(getApplicationContext(), coarseLocationPermissionString) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{coarseLocationPermissionString}, AppConstants.REQUEST_CODE_LOCATION_ZOOM);
        } else {
            // Permission has already been granted
            Timber.d("Location permisson granted");
            final LocationRequest locationRequest;

            final long UPDATE_INTERVAL = 1000; /* 1 secs */
            final long MAX_WAIT_TIME = 2000; /* 2 secs */
            final long EXPIRE_TIME = 5000; /* 5 secs */

            // Create the location request to start receiving updates
            locationRequest = new LocationRequest();
            locationRequest.setPriority(LocationRequest.PRIORITY_LOW_POWER);
            locationRequest.setInterval(UPDATE_INTERVAL);
            locationRequest.setMaxWaitTime(MAX_WAIT_TIME);
            locationRequest.setExpirationDuration(EXPIRE_TIME);
            locationRequest.setNumUpdates(1); //update only once

            // Create LocationSettingsRequest object using location request
            LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
            builder.addLocationRequest(locationRequest);
            LocationSettingsRequest locationSettingsRequest = builder.build();

            // Check whether location settings are satisfied
            // https://developers.google.com/android/reference/com/google/android/gms/location/SettingsClient
            SettingsClient settingsClient = LocationServices.getSettingsClient(getApplicationContext());
            settingsClient.checkLocationSettings(locationSettingsRequest);
            LocationServices.getFusedLocationProviderClient(getApplicationContext()).requestLocationUpdates(locationRequest, new LocationCallback() {
                        @Override
                        public void onLocationResult(LocationResult locationResult) {
                            Location location = locationResult.getLastLocation();
                            if (location != null) {
                                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

                                CameraPosition cameraPosition = new CameraPosition.Builder()
                                        .target(latLng)
                                        .zoom(AppConstants.DEFAULT_MAP_ZOOM)
                                        .build();
                                if (mMap != null) {
                                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                                }
                            }

                        }
                    },
                    Looper.myLooper());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case AppConstants.REQUEST_CODE_LOCATION_ZOOM: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay!
                    zoomToCurrentLocation();
                } else {
                    // permission denied, boo!
                    Timber.d("Zoom to current location not allowed");
                }
                break;
            }
            case AppConstants.REQUEST_CODE_LOCATION_FILTER: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    createFilterFromCurrentLocation();
                } else {
                    Timber.d("Could not get location for filter");
                    editEmptyAprsFilter("");
                }
                break;
            }

            // other 'case' lines to check for other
            // permissions this app might request.
        }

    }

    private void changeMapType() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String mapType = sharedPreferences.getString(getString(R.string.key_map_type_preference), getString(R.string.terrain));
        if (mMap != null) {
            if (mapType.equals(getString(R.string.hybrid))) {
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            } else if (mapType.equals(getString(R.string.satellite))) {
                mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
            } else {
                mMap.setMapType(GoogleMap.MAP_TYPE_TERRAIN);
            }
        }
    }

    private void changeKeepScreenOn() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (sharedPreferences.getBoolean(getString(R.string.key_keepscreenon_preference), false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void changeAircraftTimeout() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String timeout = sharedPreferences.getString(getString(R.string.key_aircraft_timeout_preference), getString(R.string.time_5m));
        int timoutInSec = AppConstants.DEFAULT_AIRCRAFT_TIMEOUT_IN_SEC;
        if (timeout.equals(getString(R.string.time_30s))) {
            timoutInSec = 30;
        } else if (timeout.equals(getString(R.string.time_1m))){
            timoutInSec = 60;
        } else if (timeout.equals(getString(R.string.time_2m))){
            timoutInSec = 120;
        } else if (timeout.equals(getString(R.string.time_5m))){
            timoutInSec = 300;
        } else if (timeout.equals(getString(R.string.time_10m))){
            timoutInSec = 600;
        } else if (timeout.equals(getString(R.string.time_30m))){
            timoutInSec = 1800;
        } else if (timeout.equals(getString(R.string.time_1h))){
            timoutInSec = 3600;
        }
        if (ognService != null) {
            ognService.setAircraftTimeout(timoutInSec);
        }
    }

    private void changeTCPServerState() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Boolean active = sharedPreferences.getBoolean(getString(R.string.key_tcp_server_active_preference), false);

        if (ognService != null) {
            if (active) {
                ognService.startLocationUpdates(this);
            } else {
                ognService.stopLocationUpdates();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.fragment_maps);
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
        changeKeepScreenOn();

        //TODO: why is this necessary in onCreate? dominik: 2017-11-12
        checkSetUpMap();//already in onResume(), but seems to be not enough

        if (savedInstanceState == null) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String aprsFilter = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");
            if (aprsFilter.equals("")) {
                createFilterFromCurrentLocation();
            } else {
                startService(new Intent(getBaseContext(), OgnService.class));
            }
        }
    }

    private void createFilterFromCurrentLocation() {
        final String coarseLocationPermissionString = Manifest.permission.ACCESS_COARSE_LOCATION;

        if (ContextCompat.checkSelfPermission(getApplicationContext(), coarseLocationPermissionString) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{coarseLocationPermissionString}, AppConstants.REQUEST_CODE_LOCATION_FILTER);
        } else {
            // Permission has already been granted
            Timber.d("Location permisson granted");
            final LocationRequest locationRequest;

            long UPDATE_INTERVAL = 1000; /* 1 secs */
            long MAX_WAIT_TIME = 2000; /* 2 secs */
            long EXPIRE_TIME = 3000;  /* 3 secs */

            // Create the location request to start receiving updates
            locationRequest = new LocationRequest();
            locationRequest.setPriority(LocationRequest.PRIORITY_LOW_POWER);
            locationRequest.setInterval(UPDATE_INTERVAL);
            locationRequest.setMaxWaitTime(MAX_WAIT_TIME);
            locationRequest.setExpirationDuration(EXPIRE_TIME);
            locationRequest.setNumUpdates(1); //update only once

            // Create LocationSettingsRequest object using location request
            LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
            builder.addLocationRequest(locationRequest);
            LocationSettingsRequest locationSettingsRequest = builder.build();

            // Check whether location settings are satisfied
            // https://developers.google.com/android/reference/com/google/android/gms/location/SettingsClient
            SettingsClient settingsClient = LocationServices.getSettingsClient(getApplicationContext());
            settingsClient.checkLocationSettings(locationSettingsRequest);

            emptyFilterTimer = new CountDownTimer(EXPIRE_TIME, 1000) {

                @Override
                public void onTick(long millisUntilFinished) {
                    // do nothing
                }

                @Override
                public void onFinish() {
                    // location update timed out
                    editEmptyAprsFilter("");
                }
            }.start();

            LocationServices.getFusedLocationProviderClient(getApplicationContext()).requestLocationUpdates(locationRequest, new LocationCallback() {
                        @Override
                        public void onLocationResult(LocationResult locationResult) {
                            if (emptyFilterTimer != null) {
                                emptyFilterTimer.cancel();
                            }
                            Location location = locationResult.getLastLocation();
                            String aprsFilter = "";
                            if (location != null) {
                                aprsFilter = AprsFilterManager.latLngToAprsFilter(location.getLatitude(), location.getLongitude());
                            }
                            editEmptyAprsFilter(aprsFilter);
                        }
                    },
                    Looper.myLooper());
        }
    }


    private void updateReceiverBeaconMarker(ReceiverBundle bundle) {
        ReceiverBeacon beacon = bundle.receiverBeacon;
        String receiverName = beacon.getId();
        double lat = beacon.getLat();
        double lon = beacon.getLon();
        float alt = beacon.getAlt();
        float recInputNoise = beacon.getRecInputNoise();
        int aircraftCounter = bundle.aircrafts.size();
        int beaconCounter = bundle.beaconCount;
        long timestamp = beacon.getTimestamp();

        updateReceiverBeaconMarkerOnMap(receiverName, lat, lon, alt, recInputNoise,
                aircraftCounter, ReceiverBundle.maxAircraftCounter,
                beaconCounter, ReceiverBundle.maxBeaconCounter, timestamp);

    }

    private void updateReceiverBeaconMarkerOnMap(String receiverName, double lat, double lon,
                                                 float altitude, float recInputNoise,
                                                 int aircraftCounter, int maxAircraftCounter,
                                                 int beaconCounter, int maxBeaconCounter, long timestamp) {
        Marker m;
        boolean infoWindowShown = false;
        if (!receiverMarkerMap.containsKey(receiverName)) {
            if (mMap == null) {
                return;
            }
            m = mMap.addMarker(new MarkerOptions().position(new LatLng(lat, lon)));
            receiverMarkerMap.put(receiverName, m);
        } else {
            m = receiverMarkerMap.get(receiverName);
            infoWindowShown = m.isInfoWindowShown();
            m.setPosition(new LatLng(lat, lon));
        }

        Timber.d("updating marker for receiver: " + receiverName + " " + new Date().getTime());

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Boolean showReceivers = sharedPreferences.getBoolean(getString(R.string.key_showreceivers_preference), false);
        Boolean isActive = (sharedPreferences.getBoolean(getString(R.string.key_shownotactive_preference), true) || aircraftCounter > 0 || beaconCounter > 0);
        String altitudeUnit = sharedPreferences.getString(getString(R.string.key_altitude_unit_preference), getString(R.string.unit_meters));

        m.setVisible(showReceivers && isActive);

        float convertedAltitude = altitude;
        if (altitudeUnit.equals(getString(R.string.unit_feet))) {
            convertedAltitude = Utils.metersToFeet(altitude);
        }
        String title = String.format(Locale.US, "%s (%.1f %s)", receiverName, convertedAltitude, altitudeUnit);
        String humanTime = DateFormat.format("HH:mm:ss", timestamp).toString();
        String content = String.format(Locale.US, "Aircraft: %d, Beacons: %d, \ntime: %s",
                aircraftCounter, beaconCounter, humanTime);

        m.setTitle(title);
        m.setSnippet(content);

        //attempt to speedup app
        if (!showReceivers) {
            return;
        }

        float hue;
        String colorisation = sharedPreferences.getString(getString(R.string.key_receiver_colorisation_preference), getString(R.string.aircraft_count));
        if (colorisation.equals(getString(R.string.aircraft_count))) {
            hue = Utils.getHue(aircraftCounter, 0, maxAircraftCounter, 0, 270);
        } else if (colorisation.equals(getString(R.string.beacon_count))) {
            hue = Utils.getHue(beaconCounter, 0, maxBeaconCounter, 0, 270);
        } else {
            hue = Utils.getHue(altitude, 0, 3000, 0, 270);
        }

        //m.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));
        IconGenerator iconGenerator = new IconGenerator(this);
        iconGenerator.setContentPadding(0, 0, 0, 0);
        Bitmap icon = iconGenerator.makeIcon(receiverName);

        int iconMinSize = 72;   // sufficient for "808"
        int delta = Math.max(0, iconMinSize - icon.getWidth()); //delta is > 0 when receiverName is very short
        iconGenerator.setContentPadding(delta / 2, 0, delta / 2, 0);
        iconGenerator.setColor(Color.HSVToColor(new float[]{hue, (float)255, (float)255}));
        iconGenerator.setTextAppearance(R.style.TextColorBlack);
        try {
            icon = null; //maybe helps to avoid OutOfMemoryError 2018-02-07
            icon = iconGenerator.makeIcon(receiverName); //CAUTION: sometimes causes OutOfMemoryError
        } catch (Exception e) { //cannot catch OutOfMemoryError
            Timber.d("updating receiver caused: " + e.getMessage());
        } catch (Throwable t) { //cannot catch OutOfMemoryError
            Timber.d("updating receiver caused an exception"); //just for testing and can be removed 2018-01-30
        }
        m.setIcon(BitmapDescriptorFactory.fromBitmap(icon));

        // (re)open the infoWindow
        if (infoWindowShown) {
            m.showInfoWindow();
        }
        Timber.d("updated marker for receiver: " + receiverName + " " + new Date().getTime());
    }

    private void updateAircraftBeaconMarker(AircraftBundle bundle) {
        AircraftBeacon aircraftBeacon = bundle.aircraftBeacon;
        AircraftDescriptor aircraftDescriptor = bundle.aircraftDescriptor;

        boolean isOgnPrivate = aircraftDescriptor.isKnown() && (!aircraftDescriptor.isTracked() || !aircraftDescriptor.isIdentified());
        if (!isOgnPrivate) {
            updateAircraftBeaconMarkerOnMap(aircraftBeacon.getAddress(), aircraftBeacon.getAircraftType(),
                    aircraftBeacon.getClimbRate(), aircraftBeacon.getLat(), aircraftBeacon.getLon(),
                    aircraftBeacon.getAlt(), aircraftBeacon.getGroundSpeed(), aircraftDescriptor.getRegNumber(),
                    aircraftDescriptor.getCN(), aircraftDescriptor.getModel(), aircraftBeacon.getReceiverName(),
                    aircraftBeacon.getTrack(), aircraftBeacon.getTimestamp());
        }

    }

    private void pauseUpdatingMap() {
        if (ognService != null) {
            ognService.pauseUpdatingMap();
        }
    }

    private void resumeUpdatingMap() {
        if (ognService != null && mMap != null) {
            ognService.resumeUpdatingMap(mMap.getProjection().getVisibleRegion().latLngBounds);
        }
    }

    private void updateAircraftBeaconMarkerOnMap(String address, AircraftType aircraftType, float climbRate,
                                            double lat, double lon, float alt, float groundSpeed,
                                            String regNumber, String CN, String model,
                                            String receiverName, int track, long timestamp) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        if (ognService == null) {
            return; //why does this happen? (sometimes during debug)
        }
        ognService.setMapUpdatingStatus(true);
        Marker m;
        boolean infoWindowShown = false;

        Timber.d("updating marker for address: " + address + " " + new Date().getTime());

        if (!aircraftMarkerMap.containsKey(address)) {
            if (mMap == null) {
                return; //happens when orientation changes
            } else {
                m = mMap.addMarker(new MarkerOptions().position(new LatLng(lat, lon)));
            }
            aircraftMarkerMap.put(address, m);
        } else {
            m = aircraftMarkerMap.get(address);

            infoWindowShown = m.isInfoWindowShown();
            m.setPosition(new LatLng(lat, lon));
        }
        Boolean rotateAircraft = sharedPreferences.getBoolean(getString(R.string.key_rotate_aircraft_preference), false);
        if (rotateAircraft) {
            m.setRotation(track + 180); //with 180 the pin shows to north on 0 degree from track
            if (track < 90 || track > 270) { // info window must not hide aircraft marker
                m.setInfoWindowAnchor(0.5f, 1f); // bottom middle
            } else {
                m.setInfoWindowAnchor(0.5f, 0f); // top middle
            }
        } else {
            m.setRotation(0); // default, to reset already rotated markers
            m.setInfoWindowAnchor(0.5f, 0f); // top middle
        }

        String colorisation = sharedPreferences.getString(getString(R.string.key_aircraft_colorisation_preference), getString(R.string.altitude));
        Boolean showaircrafts = sharedPreferences.getBoolean(getString(R.string.key_showaircrafts_preference), true);
        Boolean shownonmoving = sharedPreferences.getBoolean(getString(R.string.key_shownonmoving_preference), true);
        Boolean showregistration = sharedPreferences.getBoolean(getString(R.string.key_showregistration_preference), true);
        String altitudeUnit = sharedPreferences.getString(getString(R.string.key_altitude_unit_preference), getString(R.string.unit_meters));
        String gsUnit = sharedPreferences.getString(getString(R.string.key_groundspeed_unit_preference), getString(R.string.unit_kmh));
        String vsUnit = sharedPreferences.getString(getString(R.string.key_verticalspeed_unit_preference), getString(R.string.unit_ms));

        if (!showaircrafts || !shownonmoving && groundSpeed < 5) {
            m.setVisible(false);
            ognService.setMapUpdatingStatus(false);
            return;
        } else {
            m.setVisible(true);
        }


        // make snippet
        String title;
        if (regNumber != null && !regNumber.isEmpty()) {
            title = regNumber;
            if (model != null && !model.isEmpty()) {
                title += " (" + model + ")";
            }
        } else {
            title = address;
            //maybe add aircraftType? 2018-02-11
        }
        String humanTime = DateFormat.format("HH:mm:ss", timestamp).toString();
        int convertedAltitude = (int) alt;
        if (altitudeUnit.equals(getString(R.string.unit_feet))) {
            convertedAltitude = (int) Utils.metersToFeet(alt);
        }

        int convertedGroudSpeed = (int) groundSpeed;
        if (gsUnit.equals(getString(R.string.unit_mph))) {
            convertedGroudSpeed = (int) Utils.kmhToMph(groundSpeed);
        } else if (gsUnit.equals(getString(R.string.unit_kt))) {
            convertedGroudSpeed = (int) Utils.kmhToKt(groundSpeed);
        }

        float convertedClimbRate = climbRate;
        if (vsUnit.equals(getString(R.string.unit_fpm))) {
            convertedClimbRate = Utils.msToFpm(climbRate);
        }

        String content = String.format(Locale.US,"alt:%d %s, gs:%d %s, vs:%.1f %s, \ntime:%s, rec:%s",
                convertedAltitude, altitudeUnit, convertedGroudSpeed, gsUnit, convertedClimbRate, vsUnit, humanTime, receiverName);

        m.setTitle(title);
        m.setSnippet(content);


        // make color of the marker
        float hue = 0;
        int color = Color.rgb(255, 255, 255);
        if (colorisation.equals(getString(R.string.altitude))) {
            final float minAlt = 500.0f;
            final float maxAlt = 3000.0f;
            hue = Utils.getHue(alt, minAlt, maxAlt, 0, 270);
        } else if (colorisation.equals(getString(R.string.speed))) {
            final float minSpeed = 50.0f;
            final float maxSpeed = 285.0f;
            hue = Utils.getHue(groundSpeed, minSpeed, maxSpeed, 0, 270);
        } else if (colorisation.equals(getString(R.string.aircraft_type))) {
            switch (aircraftType) {
                //case UNKNOWN:
                //    break;
                case GLIDER:
                    hue = BitmapDescriptorFactory.HUE_YELLOW;
                    color = Color.rgb(252, 245, 70);
                    break;
                case TOW_PLANE:
                    hue = BitmapDescriptorFactory.HUE_GREEN;
                    color = Color.rgb(35, 249, 13);
                    break;
                case HELICOPTER_ROTORCRAFT:
                    hue = BitmapDescriptorFactory.HUE_RED;
                    color = Color.rgb(240, 72, 52);
                    break;
                //case PARACHUTE:
                //    break;
                //case DROP_PLANE:
                //    break;
                //case HANG_GLIDER:
                //    break;
                case PARA_GLIDER:
                    hue = BitmapDescriptorFactory.HUE_MAGENTA;  //Pink?
                    color = Color.rgb(254, 191, 193);
                    break;
                //case POWERED_AIRCRAFT:
                //    break;
                //case JET_AIRCRAFT:
                //    break;
                //case UFO:
                //    break;
                //case BALLOON:
                //    break;
                //case AIRSHIP:
                //    break;
                //case UAV:
                //    break;
                //case STATIC_OBJECT:
                //    break;
                default:
                    hue = BitmapDescriptorFactory.HUE_BLUE;
                    color = Color.rgb(25, 159, 238);

                    // gray:
                    // color = Color.rgb(218, 218, 208);
            }
        }


        // make icon
        if (!showregistration || ((regNumber == null || regNumber.isEmpty()) && (CN == null || CN.isEmpty()))) {
            if (m == null) { ///why is this sometimes true?
                Timber.wtf("m is null while updating aircraft marker");
                ognService.setMapUpdatingStatus(false);
                return;
            }
            m.setIcon(BitmapDescriptorFactory.defaultMarker(hue)); //CAUTION: very slow process!
            //m.setIcon(BitmapDescriptorFactory.defaultMarker()); //TODO: fix this workaround
        } else {
            if (CN == null || CN.isEmpty()) {
                if (regNumber.length() > 1) {
                    title = regNumber.substring(regNumber.length() - 2, regNumber.length());
                } else {
                    title = "?";
                }
            } else {
                title = CN;
            }

            IconGenerator iconGenerator = new IconGenerator(this);
            iconGenerator.setContentPadding(0, 0, 0, 0);
            Bitmap icon = iconGenerator.makeIcon(title);

            int iconMinSize = 72;   // sufficient for "808"
            int delta = Math.max(0, iconMinSize - icon.getWidth());
            iconGenerator.setContentPadding(delta / 2, 0, delta / 2, 0);
            iconGenerator.setColor(Color.HSVToColor(new float[]{hue, 255, 255}));
            iconGenerator.setTextAppearance(R.style.TextColorBlack);
            icon = iconGenerator.makeIcon(title);

            if (m == null) { //why is this sometimes true?
                Timber.wtf("m is null while updating aircraft marker");
                ognService.setMapUpdatingStatus(false);
                return;
            }
            m.setIcon(BitmapDescriptorFactory.fromBitmap(icon));
        }


        // (re)open the infoWindow
        if (infoWindowShown) {
            m.showInfoWindow();
        }
        ognService.setMapUpdatingStatus(false);
        Timber.d("updated marker for address: " + address + " " + new Date().getTime());
    }

    private void removeAircraftFromMap(String address) {
        if (aircraftMarkerMap.containsKey(address)) {
            Marker m;
            m = aircraftMarkerMap.get(address);
            if (m == null) {
                //already removed? continue
                return;
            }
            m.remove(); //remove marker from mMap
            aircraftMarkerMap.remove(address); //maybe a problem if at same time .add in updateAircraft()? 2018-02-09
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseUpdatingMap();
        unbindService(mConnection); //does not unbind when settings activity is called! 2018-01-30
        ognServiceConnected = false;
        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(this);
        if (localBroadcastManager != null) {
            //IMPORTANT: unregister intent receivers
            //otherwise there are multiple receivers for the same intent
            localBroadcastManager.unregisterReceiver(aircraftReceiver);
            localBroadcastManager.unregisterReceiver(receiverReceiver);
            localBroadcastManager.unregisterReceiver(actionReceiver);
        }

        // Save current lat, lon, zoom
        if (mMap != null) {
            float lat = (float) mMap.getCameraPosition().target.latitude;
            float lon = (float) mMap.getCameraPosition().target.longitude;
            float zoom = mMap.getCameraPosition().zoom;
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            sharedPreferences.edit().putFloat(getString(R.string.key_latitude_preference), lat).apply();
            sharedPreferences.edit().putFloat(getString(R.string.key_longitude_preference), lon).apply();
            sharedPreferences.edit().putFloat(getString(R.string.key_zoom_preference), zoom).apply();
            //speedup for onResume? oh yes :)
            mMap.clear();
        }
        //additional speedup for onResume
        rangeCircle = null;
        aircraftMarkerMap.clear();
        receiverMarkerMap.clear();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapLoaded = false;
        Timber.uprootAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //service must always be reconnected, even if ognService != null
        bindService(new Intent(this, OgnService.class), mConnection, Context.BIND_AUTO_CREATE);

        checkSetUpMap();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String aprsFilter = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");
        updateAprsFilterRange(aprsFilter); //necessary for the circle, because it was erased in onPause
        resumeUpdatingMap();

        registerBroadcastReceivers();
    }

    private void registerBroadcastReceivers() {
        aircraftReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // AircraftBeacon
                String receiverName = intent.getStringExtra("receiverName");
                //AddressType addressType = AddressType.forValue(intent.getIntExtra("addressType", 0));
                String address = intent.getStringExtra("address");
                AircraftType aircraftType = AircraftType.forValue(intent.getIntExtra("aircraftType", 0));
                //boolean stealth = intent.getBooleanExtra("stealth", false);
                float climbRate = intent.getFloatExtra("climbRate", 0);
                //float turnRate = intent.getFloatExtra("turnRate", 0);
                //float signalStrength = intent.getFloatExtra("signalStrength", 0);
                //float frequencyOffset = intent.getFloatExtra("frequencyOffset", 0);
                //String gpsStatus = intent.getStringExtra("gpsStatus");
                //int errorCount = intent.getIntExtra("errorCount", 0);
                //String[] getHeardAircraftIds();

                // OgnBeacon
                //String id = intent.getStringExtra("id");
                long timestamp = intent.getLongExtra("timestamp", 0);
                double lat = intent.getDoubleExtra("lat", 0);
                double lon = intent.getDoubleExtra("lon", 0);
                float alt = intent.getFloatExtra("alt", 0);
                int track = intent.getIntExtra("track", 0);
                float groundSpeed = intent.getFloatExtra("groundSpeed", 0);
                //String rawPacket = intent.getStringExtra("rawPacket");

                // AircraftDescriptor
                boolean known = intent.getBooleanExtra("known", false);
                String regNumber = intent.getStringExtra("regNumber");
                String CN = intent.getStringExtra("CN");
                //String owner = intent.getStringExtra("owner");
                //String homeBase = intent.getStringExtra("homeBase");
                String model = intent.getStringExtra("model");
                //String freq = intent.getStringExtra("freq");
                boolean tracked = intent.getBooleanExtra("tracked", false);
                boolean identified = intent.getBooleanExtra("identified", false);

                boolean isOgnPrivate = known && (!tracked || !identified);
                if (!isOgnPrivate) {
                    updateAircraftBeaconMarkerOnMap(address, aircraftType, climbRate, lat, lon, alt,
                            (int) groundSpeed, regNumber, CN, model, receiverName, track, timestamp);
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver((aircraftReceiver), new IntentFilter("AIRCRAFT-BEACON"));

        receiverReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // ReceiverBeacon
                float recInputNoise = intent.getFloatExtra("recInputNoise", 0);
                //String version = intent.getStringExtra("version");
                //String platform = intent.getStringExtra("platform");
                //int numericVersion = intent.getIntExtra("numericVersion", 0);

                // OgnBeacon
                String id = intent.getStringExtra("id");
                long timestamp = intent.getLongExtra("timestamp", 0);
                double lat = intent.getDoubleExtra("lat", 0);
                double lon = intent.getDoubleExtra("lon", 0);
                float alt = intent.getFloatExtra("alt", 0);

                // Computed values
                int aircraftCounter = intent.getIntExtra("aircraftCounter", 0);
                int maxAircraftCounter = intent.getIntExtra("maxAircraftCounter", 0);

                int beaconCounter = intent.getIntExtra("beaconCounter", 0);
                int maxBeaconCounter = intent.getIntExtra("maxBeaconCounter", 0);
                if (lat != 0 && lon != 0) {
                    updateReceiverBeaconMarkerOnMap(id, lat, lon, alt, recInputNoise,
                            aircraftCounter, maxAircraftCounter, beaconCounter, maxBeaconCounter, timestamp);
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver((receiverReceiver), new IntentFilter("RECEIVER-BEACON"));

        //action receiver for receiving commands from ognService
        actionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getStringExtra("AIRCRAFT_ACTION");
                if (action.equals("REMOVE_AIRCRAFT")) {
                    String address = intent.getStringExtra("address");
                    if (address != null) {
                        removeAircraftFromMap(address);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver((actionReceiver), new IntentFilter("AIRCRAFT_ACTION"));
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void checkSetUpMap() {
        if (mMap == null) {
            SupportMapFragment suppMapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
            suppMapFragment.getMapAsync(this);
        }
    }

    private void setUpMap() {
        changeMapType();
        changeAircraftTimeout();
        mMap.getUiSettings().setMapToolbarEnabled(false);

        mMap.setOnInfoWindowClickListener(new GoogleMap.OnInfoWindowClickListener() {
            @Override
            public void onInfoWindowClick(Marker marker) {
                if (receiverMarkerMap.containsValue(marker)) {
                    return; //Don't show a dialog when info window of a receiver is clicked.
                }
                String address = "";
                for (Map.Entry<String, Marker> entry : aircraftMarkerMap.entrySet()) {
                    if (entry.getValue().equals(marker)) {
                        address = entry.getKey();
                        break;
                    }
                }

                AircraftDialog.showDialog(MapsActivity.this, address);
            }
        });

        mMap.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(LatLng latLng) {
                String aprsFilter = AprsFilterManager.latLngToAprsFilter(latLng.latitude, latLng.longitude);
                editAprsFilter(aprsFilter);
            }
        });
    }

    private void editAprsFilter(final String aprsFilter) {
        View view = getLayoutInflater().inflate(R.layout.dialog_aprsfilter, null);
        final EditText et = view.findViewById(R.id.editTextOwner);
        et.setText(aprsFilter);

        new AlertDialog.Builder(this).setView(view)
                .setTitle(R.string.aprs_filter_title)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String aprsFilterModified = et.getText().toString();

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        String aprsFilterSaved = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");

                        if (!aprsFilterModified.equals(aprsFilterSaved)) {
                            applyModifiedAprsFilter(aprsFilterModified);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        // do nothing
                    }
                })
                .show();
    }

    private void editEmptyAprsFilter(final String aprsFilter) {
        View view = getLayoutInflater().inflate(R.layout.dialog_aprsfilter, null);
        final EditText et = view.findViewById(R.id.editTextOwner);
        et.setText(aprsFilter);

        new AlertDialog.Builder(MapsActivity.this).setView(view)
                .setTitle(R.string.aprs_filter_title)
                .setMessage(R.string.empty_aprs_filter_message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String aprsFilterModified = et.getText().toString();
                        applyModifiedAprsFilter(aprsFilterModified);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        Toast.makeText(getApplicationContext(), R.string.empty_aprs_filter_toast, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void applyModifiedAprsFilter(String aprsFilterModified) {
        if (aprsFilterModified == null) {
            return;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        sharedPreferences.edit().putString(getString(R.string.key_aprsfilter_preference), aprsFilterModified).apply();
        startService(new Intent(getBaseContext(), OgnService.class));
        //resumeUpdatingMap is called within OgnService onStartCommand, don't call it here! async!
        updateAprsFilterRange(aprsFilterModified);
    }

    private void updateAprsFilterRange(String aprsFilter) {
        if (rangeCircle == null) {
            if (mMap == null) {
                return;
            } else {
                rangeCircle = mMap.addCircle(new CircleOptions().center(new LatLng(0, 0)).radius(1).strokeColor(Color.RED));
            }
        }
        rangeCircle.setVisible(false);

        AprsFilterManager.Circle circle = AprsFilterManager.parse(aprsFilter);
        if (circle != null) {
            rangeCircle.setCenter(new LatLng(circle.lat, circle.lon));
            rangeCircle.setRadius(circle.radius * 1000);
            rangeCircle.setVisible(true);
        }
    }

    /**
     * Is called when orientation changed, not called when app was in background
     * @param googleMap GoogleMapsObject
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (mMap != null) {
            //second call of onMapReady
            //it is called in onCreate and onResume, check if one call is enough 2018-02-11
            //mMap is already set
            //maybe good time to reload markers?
            if (ognService != null) {
                //TODO: Do not delete WIP 2018-02-11
                //doesn't work after app to background or first start...
                //updateKnownMarkers();
            }
            return; //use first loaded map only
        }
        mMap = googleMap;
        mMap.getUiSettings().setRotateGesturesEnabled(false); //do not allow turning the map with fingers
        mMap.setOnCameraIdleListener(this);
        mMap.setOnCameraMoveStartedListener(this);
        mMap.setOnMapLoadedCallback(this);
        setUpMap();

        // Restore lat, lon, zoom
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        float lat = sharedPreferences.getFloat(getString(R.string.key_latitude_preference), 0.0f);
        float lon = sharedPreferences.getFloat(getString(R.string.key_longitude_preference), 0.0f);
        float zoom = sharedPreferences.getFloat(getString(R.string.key_zoom_preference), 2.0f);
        CameraPosition cameraPosition = new CameraPosition.Builder()
                .target(new LatLng(lat, lon))
                .zoom(zoom)
                .build();
        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

        //Multiline Info Window from https://code.sololearn.com/cro931h7zM2c/#java
        mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override
            public View getInfoWindow(Marker arg0) {
                return null;
            }
            @Override
            public View getInfoContents(Marker marker) {
                Context context = getApplicationContext(); //or getActivity(), YourActivity.this, etc.
                LinearLayout info = new LinearLayout(context);
                info.setOrientation(LinearLayout.VERTICAL);

                TextView title = new TextView(context);
                title.setTextColor(Color.BLACK);
                title.setGravity(Gravity.CENTER);
                title.setTypeface(null, Typeface.BOLD);
                title.setText(marker.getTitle());

                TextView snippet = new TextView(context);
                snippet.setTextColor(Color.GRAY);
                snippet.setText(marker.getSnippet());

                info.addView(title);
                info.addView(snippet);

                return info;
            }
        });

        String aprsFilter = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");
        updateAprsFilterRange(aprsFilter);
    }

    @Override
    public void onCameraMoveStarted(int i) {
        pauseUpdatingMap();
    }

    @Override
    public void onCameraIdle() {
        resumeUpdatingMap();
    }

    @Override
    public void onMapLoaded() {
        //not called e.g. after settings activity!
        Timber.d("map loaded");
        mapLoaded = true;
        updateKnownMarkers();
    }

    private void updateKnownMarkers() {
        //CAUTION: the update functions are very slow and take a few seconds (app seems to be frozen)
        if (ognServiceConnected && mapLoaded) {
            pauseUpdatingMap();
            Timber.d("start reloading markers");
            updateKnownAircrafts(ognService.aircraftBundleMap);
            updateKnownReceivers(ognService.receiverBundleMap);
            Timber.d("reloaded markers");
            resumeUpdatingMap();
        }
    }
}
