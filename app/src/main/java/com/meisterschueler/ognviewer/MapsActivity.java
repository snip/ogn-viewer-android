package com.meisterschueler.ognviewer;

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
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import com.google.android.gms.location.LocationListener;
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
import com.meisterschueler.ognviewer.common.ReceiverBundle;
import com.meisterschueler.ognviewer.common.Utils;

import org.ogn.commons.beacon.AddressType;
import org.ogn.commons.beacon.AircraftBeacon;
import org.ogn.commons.beacon.AircraftDescriptor;
import org.ogn.commons.beacon.AircraftType;
import org.ogn.commons.beacon.ReceiverBeacon;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback,
        GoogleMap.OnCameraMoveStartedListener, GoogleMap.OnCameraIdleListener {

    private static final String TAG = "MapsActivity";

    private OgnService ognService;
    private Circle rangeCircle;
    private BroadcastReceiver aircraftReceiver;
    private BroadcastReceiver receiverReceiver;
    private BroadcastReceiver actionReceiver;
    private Map<String, Marker> aircraftMarkerMap = new HashMap<>();
    private Map<String, Marker> receiverMarkerMap = new HashMap<>();
    private GoogleMap mMap; // Might be null if Google Play services APK is not available.
    private ServiceConnection mConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder binder) {
            OgnService.LocalBinder localBinder = (OgnService.LocalBinder) binder;
            ognService = localBinder.getService();
            updateKnownAircrafts(ognService.aircraftBundleMap);
            updateKnownReceivers(ognService.receiverBundleMap);
        }

        public void onServiceDisconnected(ComponentName className) {
            ognService = null;
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

    boolean mapUpdating = false;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    final int OPTION_SETTINGS = 2;

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        final int id = item.getItemId();

        switch (id) {
            case R.id.action_settings:
                Intent i = new Intent(this, PrefsActivity.class);
                startActivityForResult(i, OPTION_SETTINGS);
                break;
            case R.id.action_manageids:
                Intent i2 = new Intent(this, ManageIDsActivity.class);
                startActivity(i2);
                break;
            case R.id.action_currentlocation:
                LocationManager locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

                LocationListener locationListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

                        CameraPosition cameraPosition = new CameraPosition.Builder()
                                .target(latLng)
                                .zoom(7)
                                .build();
                        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                    }
                };
                try {
                    Location location = locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (location != null) {
                        locationListener.onLocationChanged(location);
                    } else {
                        //locManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, null);
                    }
                } catch (SecurityException se) {
                    // accessing location is forbidden
                }

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
                LocalBroadcastManager.getInstance(this).unregisterReceiver(aircraftReceiver);
                LocalBroadcastManager.getInstance(this).unregisterReceiver(receiverReceiver);
                LocalBroadcastManager.getInstance(this).unregisterReceiver(actionReceiver);
                finish();
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == OPTION_SETTINGS)
        {
            //aprs filter
            //String message = data.getStringExtra("MESSAGE"); //leave this for future usage
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String aprsFilter = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");
            updateAprsFilterRange(aprsFilter);

            //map type
            changeMapType();

            //receivers
            Boolean showreceivers = sharedPreferences.getBoolean(getString(R.string.key_showreceivers_preference), true);
            for (Marker m : receiverMarkerMap.values()) { //TODO: profile this code, maybe slow!
                m.setVisible(showreceivers);
            }
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.fragment_maps);
        checkSetUpMap();


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
                //long timestamp = intent.getLongExtra("timestamp", 0);
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
                            (int) groundSpeed, regNumber, CN, model, receiverName, track);
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
                //long timestamp = intent.getLongExtra("timestamp", 0);
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
                            aircraftCounter, maxAircraftCounter, beaconCounter, maxBeaconCounter);
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


        if (savedInstanceState == null) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            String aprsFilter = sharedPreferences.getString(getString(R.string.key_aprsfilter_preference), "");
            if (aprsFilter.equals("")) {
                LocationManager locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (locManager != null) {
                    try {
                        Location location = locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        if (location != null) {
                            aprsFilter = AprsFilterManager.latLngToAprsFilter(location.getLatitude(), location.getLongitude());
                        }
                    } catch (SecurityException se) {
                        // accessing location is forbidden
                    }
                }
                editEmptyAprsFilter(aprsFilter);

            } else {
                startService(new Intent(getBaseContext(), OgnService.class));
            }
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

        updateReceiverBeaconMarkerOnMap(receiverName, lat, lon, alt, recInputNoise,
                aircraftCounter, ReceiverBundle.maxAircraftCounter,
                beaconCounter, ReceiverBundle.maxBeaconCounter);

    }

    private void updateReceiverBeaconMarkerOnMap(String receiverName, double lat, double lon,
                                                 float altitude, float recInputNoise,
                                                 int aircraftCounter, int maxAircraftCounter,
                                                 int beaconCounter, int maxBeaconCounter ) {
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

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Boolean showReceivers = sharedPreferences.getBoolean(getString(R.string.key_showreceivers_preference), false);
        Boolean isActive = (sharedPreferences.getBoolean(getString(R.string.key_shownotactive_preference), true) || aircraftCounter > 0 || beaconCounter > 0);

        m.setVisible(showReceivers && isActive);

        String title = receiverName + " (" + altitude + "m)";
        String content = "Aircrafts: " + aircraftCounter + ", Beacons: " + beaconCounter;

        m.setTitle(title);
        m.setSnippet(content);

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
        int delta = Math.max(0, iconMinSize - icon.getWidth());
        iconGenerator.setContentPadding(delta / 2, 0, delta / 2, 0);
        iconGenerator.setColor(Color.HSVToColor(new float[]{hue, (float)255, (float)255}));
        iconGenerator.setTextAppearance(R.style.TextColorBlack);
        icon = iconGenerator.makeIcon(receiverName);

        m.setIcon(BitmapDescriptorFactory.fromBitmap(icon));

        // (re)open the infoWindow
        if (infoWindowShown) {
            m.showInfoWindow();
        }
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
                    aircraftBeacon.getTrack());
        }

    }




    private void pauseUpdatingMap() {
        if (ognService != null) {
            ognService.pauseUpdatingMap();
        }
    }

    private void resumeUpdatingMap() {
        if (ognService != null) {
            ognService.resumeUpdatingMap(mMap.getProjection().getVisibleRegion().latLngBounds);
        }
    }

    private void updateAircraftBeaconMarkerOnMap(String address, AircraftType aircraftType, float climbRate,
                                            double lat, double lon, float alt, float groundSpeed,
                                            String regNumber, String CN, String model,
                                            String receiverName, int track) {
        if (ognService == null) {
            return; //why does this happen? (sometimes during debug)
        }
        ognService.mapUpdatingStatus(true);
        Marker m;
        boolean infoWindowShown = false;

        Log.d(TAG, "updating marker for address: " + address + " " + new Date().getTime());

        if (!aircraftMarkerMap.containsKey(address)) {
            m = mMap.addMarker(new MarkerOptions().position(new LatLng(lat, lon)));
            aircraftMarkerMap.put(address, m);
        } else {
            m = aircraftMarkerMap.get(address);

            infoWindowShown = m.isInfoWindowShown();
            m.setPosition(new LatLng(lat, lon));
        }
        m.setRotation(track + 180); //with 180 the pin shows to north on 0 degree from track

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String colorisation = sharedPreferences.getString(getString(R.string.key_aircraft_colorisation_preference), getString(R.string.altitude));
        Boolean showaircrafts = sharedPreferences.getBoolean(getString(R.string.key_showaircrafts_preference), true);
        Boolean shownonmoving = sharedPreferences.getBoolean(getString(R.string.key_shownonmoving_preference), true);
        Boolean showregistration = sharedPreferences.getBoolean(getString(R.string.key_showregistration_preference), true);

        if (!showaircrafts || !shownonmoving && groundSpeed < 5) {
            m.setVisible(false);
            ognService.mapUpdatingStatus(false);
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
        }
        String content = String.format(Locale.US,"alt:%d gs:%d, vs:%.1f, rec:%s",
                (int) alt, (int) groundSpeed, climbRate, receiverName);

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
                ognService.mapUpdatingStatus(false);
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
                ognService.mapUpdatingStatus(false);
                return;
            }
            m.setIcon(BitmapDescriptorFactory.fromBitmap(icon));
        }


        // (re)open the infoWindow
        if (infoWindowShown) {
            m.showInfoWindow();
        }
        ognService.mapUpdatingStatus(false);
        Log.d(TAG, "updated marker for address: " + address + " " + new Date().getTime());
    }

    private void removeAircraftFromMap(String address) {
        if (aircraftMarkerMap.containsKey(address)) {
            Marker m;
            m = aircraftMarkerMap.get(address);
            if (m == null) {
                //alredy removed? continue
                return;
            }
            m.remove(); //remove marker from mMap
            aircraftMarkerMap.remove(address);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindService(mConnection);

        // Save current lat, lon, zoom
        if (mMap != null) {
            float lat = (float) mMap.getCameraPosition().target.latitude;
            float lon = (float) mMap.getCameraPosition().target.longitude;
            float zoom = mMap.getCameraPosition().zoom;
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            sharedPreferences.edit().putFloat(getString(R.string.key_latitude_preference), lat).apply();
            sharedPreferences.edit().putFloat(getString(R.string.key_longitude_preference), lon).apply();
            sharedPreferences.edit().putFloat(getString(R.string.key_zoom_preference), zoom).apply();
        }

        pauseUpdatingMap();
    }

    @Override
    protected void onResume() {
        //TODO: check what is so slow (sometimes a few seconds)
        super.onResume();
        bindService(new Intent(this, OgnService.class), mConnection, Context.BIND_AUTO_CREATE);

        checkSetUpMap();
        resumeUpdatingMap();
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
        mMap.getUiSettings().setMapToolbarEnabled(false);

        mMap.setOnInfoWindowClickListener(new GoogleMap.OnInfoWindowClickListener() {
            @Override
            public void onInfoWindowClick(Marker marker) {

                if (receiverMarkerMap.containsValue(marker))
                    return;

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
                            sharedPreferences.edit().putString(getString(R.string.key_aprsfilter_preference), aprsFilterModified).apply();
                            startService(new Intent(getBaseContext(), OgnService.class));
                            updateAprsFilterRange(aprsFilterModified);
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

        new AlertDialog.Builder(this).setView(view)
                .setTitle(R.string.aprs_filter_title)
                .setMessage(R.string.empty_aprs_filter_message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String aprsFilterModified = et.getText().toString();

                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                        sharedPreferences.edit().putString(getString(R.string.key_aprsfilter_preference), aprsFilterModified).apply();
                        startService(new Intent(getBaseContext(), OgnService.class));
                        updateAprsFilterRange(aprsFilterModified);
                    }
                })
                .show();
    }

    private void updateAprsFilterRange(String aprsFilter) {
        if (rangeCircle == null) {
            rangeCircle = mMap.addCircle(new CircleOptions().center(new LatLng(0, 0)).radius(1).strokeColor(Color.RED));
        }
        rangeCircle.setVisible(false);

        AprsFilterManager.Circle circle = AprsFilterManager.parse(aprsFilter);
        if (circle != null) {
            rangeCircle.setCenter(new LatLng(circle.lat, circle.lon));
            rangeCircle.setRadius(circle.radius * 1000);
            rangeCircle.setVisible(true);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if (mMap != null) return; //use first loaded map only
        mMap = googleMap;
        mMap.getUiSettings().setRotateGesturesEnabled(false); //do not allow rotating the map
        mMap.setOnCameraIdleListener(this);
        mMap.setOnCameraMoveStartedListener(this);
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
}
