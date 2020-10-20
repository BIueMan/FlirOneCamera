/*
 * ******************************************************************
 * @title FLIR THERMAL SDK
 * @file MainActivity.java
 * @Author FLIR Systems AB
 *
 * @brief  Main UI of test application
 *
 * Copyright 2019:    FLIR Systems
 * ******************************************************************/
package com.samples.flironecamera;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.log.ThermalLog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Sample application for scanning a FLIR ONE or a built in emulator
 * <p>
 * See the {@link CameraHandler} for how to preform discovery of a FLIR ONE camera, connecting to it and start streaming images
 * <p>
 * The MainActivity is primarily focused to "glue" different helper classes together and updating the UI components
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
public class MainActivity extends AppCompatActivity {
    //todo: creat video
    //todo: find a way to get raw thermal image
    //todo: keep main activity running when we open a new activity
    //todo: make flir-one auto-connect

    private static final String TAG = "MainActivity";

    // saving stream
    OutputStream outputStream = null;

    //Handles network camera operations
    private CameraHandler cameraHandler;

    private Identity connectedIdentity = null;
    private TextView connectionStatus;
    private TextView discoveryStatus;

    private ImageView fusImage;
    private ImageView dcImage;
    private Bitmap fusImageBitmap;
    private Bitmap dcImageBitmap;

    // saved image DC
    private ImageView photoImageSaved;

    private LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue(21);
    private UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();

    // what mode to use
    private String ThermalMode = "Thermal Only";
    private TextView thermalModeText;

    /**
     * Show message on the screen
     */
    public interface ShowMessage {
        void show(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ThermalLog.LogLevel enableLoggingInDebug = BuildConfig.DEBUG ? ThermalLog.LogLevel.DEBUG : ThermalLog.LogLevel.NONE;

        //ThermalSdkAndroid has to be initiated from a Activity with the Application Context to prevent leaking Context,
        // and before ANY using any ThermalSdkAndroid functions
        //ThermalLog will show log from the Thermal SDK in standards android log framework
        ThermalSdkAndroid.init(getApplicationContext(), enableLoggingInDebug);

        cameraHandler = new CameraHandler();

        // build txt file for data use
        setDataFile();
        dataReadThermalMode();

        // to update the page
        setupViews();
        showSDKversion(ThermalSdkAndroid.getVersion());
    }

    private void dataReadThermalMode() {
        // read ThermalMode
        String temp = textHendler.find_line_txt(textHendler.Data_Path, textHendler.Data_txt, textHendler.Fusion_Mode_Line);
        if(temp != null) {
            ThermalMode = temp;
        }
        else {
            ThermalMode = "Thermal Only";
            Log.d(TAG, "fail to load data from txt file");
        }
    }

    private void setDataFile() {
        // check if file is not already exist
        File filepath = Environment.getExternalStorageDirectory();
        File file = new File(filepath.getAbsolutePath() + textHendler.Data_Path + textHendler.Data_txt + ".txt");
        if(file == null || !file.exists()) {
            String timeStamp = new SimpleDateFormat("dd-MM-yyyy__HH-mm-ss-SSS").format(new Date());
            String fusionMode = textHendler.Fusion_Mode_Line + ":Thermal Only";
            String text = "DATE:" + timeStamp + "\n" + fusionMode;
            textHendler.save_txt(textHendler.Data_Path, textHendler.Data_txt, text);
        }
    }

    public void startDiscovery(View view) {
        startDiscovery();

        // delay before connect to the camera
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                connect(cameraHandler.getFlirOne());
            }
        }, 1000);

    }

    public void stopDiscovery(View view) {
        disconnect();

        // delay before disconnect from the camera
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                stopDiscovery();
            }
        }, 500);
    }


    /**
     * Connect to a Camera
     */
    private void connect(Identity identity) {
        //We don't have to stop a discovery but it's nice to do if we have found the camera that we are looking for
        cameraHandler.stopDiscovery(discoveryStatusListener);

        if (connectedIdentity != null) {
            Log.d(TAG, "connect(), in *this* code sample we only support one camera connection at the time");
            showMessage.show("connect(), in *this* code sample we only support one camera connection at the time");
            return;
        }

        if (identity == null) {
            Log.d(TAG, "connect(), can't connect, no camera available");
            showMessage.show("connect(), can't connect, no camera available");
            return;
        }

        connectedIdentity = identity;

        updateConnectionText(identity, "CONNECTING");
        //IF your using "USB_DEVICE_ATTACHED" and "usb-device vendor-id" in the Android Manifest
        // you don't need to request permission, see documentation for more information
        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }

    }

    private UsbPermissionHandler.UsbPermissionListener permissionListener = new UsbPermissionHandler.UsbPermissionListener() {
        @Override
        public void permissionGranted(Identity identity) {
            doConnect(identity);
        }

        @Override
        public void permissionDenied(Identity identity) {
            MainActivity.this.showMessage.show("Permission was denied for identity ");
        }

        @Override
        public void error(UsbPermissionHandler.UsbPermissionListener.ErrorType errorType, final Identity identity) {
            MainActivity.this.showMessage.show("Error when asking for permission for FLIR ONE, error:"+errorType+ " identity:" +identity);
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, connectionStatusListener);
                runOnUiThread(() -> {
                    updateConnectionText(identity, "CONNECTED");
                    cameraHandler.startStream(streamDataListener);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Log.d(TAG, "Could not connect: " + e);
                    updateConnectionText(identity, "DISCONNECTED");
                });
            }
        }).start();
    }

    /**
     * Disconnect to a camera
     */
    private void disconnect() {
        updateConnectionText(connectedIdentity, "DISCONNECTING");
        connectedIdentity = null;
        Log.d(TAG, "disconnect() called with: connectedIdentity = [" + connectedIdentity + "]");
        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> {
                    updateConnectionText(null, "DISCONNECTED");
            });
        }).start();
    }

    /**
     * Update the UI text for connection status
     */
    private void updateConnectionText(Identity identity, String status) {
        String deviceId = identity != null ? identity.deviceId : "";
        connectionStatus.setText(getString(R.string.connection_status_text, deviceId + " " + status));
    }

    /**
     * Start camera discovery
     */
    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    /**
     * Stop camera discovery
     */
    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    /**
     * Callback for discovery status, using it to update UI
     */
    private CameraHandler.DiscoveryStatus discoveryStatusListener = new CameraHandler.DiscoveryStatus() {
        @Override
        public void started() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "discovering"));
        }

        @Override
        public void stopped() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "not discovering"));
        }
    };

    /**
     * Camera connecting state thermalImageStreamListener, keeps track of if the camera is connected or not
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private ConnectionStatusListener connectionStatusListener = new ConnectionStatusListener() {
        @Override
        public void onDisconnected(@org.jetbrains.annotations.Nullable ErrorCode errorCode) {
            Log.d(TAG, "onDisconnected errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateConnectionText(connectedIdentity, "DISCONNECTED");
                }
            });
        }
    };

    private final CameraHandler.StreamDataListener streamDataListener = new CameraHandler.StreamDataListener() {

        @Override
        public void images(FrameDataHolder dataHolder) {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    fusImage.setImageBitmap(dataHolder.fusBitmap);
                    dcImage.setImageBitmap(dataHolder.dcBitmap);
                    fusImageBitmap = dataHolder.fusBitmap;
                    dcImageBitmap = dataHolder.dcBitmap;
                }
            });
        }

        @Override
        public void images(Bitmap dcBitmap, Bitmap fusBitmap) {

            try {
                framesBuffer.put(new FrameDataHolder(dcBitmap,fusBitmap));
            } catch (InterruptedException e) {
                //if interrupted while waiting for adding a new item in the queue
                Log.e(TAG,"images(), unable to add incoming images to frames buffer, exception:"+e);
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG,"framebuffer size:"+framesBuffer.size());
                    FrameDataHolder poll = framesBuffer.poll();
                    fusImage.setImageBitmap(poll.fusBitmap);
                    dcImage.setImageBitmap(poll.dcBitmap);
                    fusImageBitmap = poll.fusBitmap;
                    dcImageBitmap = poll.dcBitmap;
                }
            });

        }
    };

    /**
     * Camera Discovery thermalImageStreamListener, is notified if a new camera was found during a active discovery phase
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(Identity identity) {
            Log.d(TAG, "onCameraFound identity:" + identity);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cameraHandler.add(identity);
                }
            });
        }

        @Override
        public void onDiscoveryError(CommunicationInterface communicationInterface, ErrorCode errorCode) {
            Log.d(TAG, "onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopDiscovery();
                    MainActivity.this.showMessage.show("onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);
                }
            });
        }
    };

    private ShowMessage showMessage = new ShowMessage() {
        @Override
        public void show(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private void showSDKversion(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_version);
        String sdkVersionText = getString(R.string.sdk_version_text, version);
        sdkVersionTextView.setText(sdkVersionText);
    }

    private void setupViews() {
        connectionStatus = findViewById(R.id.connection_status_text);
        discoveryStatus = findViewById(R.id.discovery_status);

        fusImage = findViewById(R.id.msx_image);
        dcImage = findViewById(R.id.photo_image);

        photoImageSaved = findViewById(R.id.photo_image_saved);

        // button mode
        thermalModeText = findViewById(R.id.change_mode);
        thermalModeText.setText(ThermalMode);
    }

    // todo: make this function run on a fread
    public void saveImage(View view) {
        // get image

        if(dcImageBitmap == null || fusImageBitmap == null){
            Toast.makeText(getApplicationContext(), "no image exist yet", Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap dcSave = dcImageBitmap;
        Bitmap fusSave = fusImageBitmap;
        photoImageSaved.setImageBitmap(dcSave); // update the user "save" photo

        // load path
        File filepath = Environment.getExternalStorageDirectory();
        // create main dir
        File dir = new File(filepath.getAbsolutePath()+"/SIPL_FlirOne/");
        dir.mkdir();
        // create sub dirs
        File dir_dc = new File(filepath.getAbsolutePath()+"/SIPL_FlirOne/dc");
        dir_dc.mkdir();
        File dir_fus = new File(filepath.getAbsolutePath()+"/SIPL_FlirOne/fus");
        dir_fus.mkdir();
        // create files path
        String timeStamp = new SimpleDateFormat("dd-MM-yyyy__HH-mm-ss-SSS").format(new Date());
        File file_dc = new File(dir_dc, "DC_"+timeStamp+".png");
        File file_fus = new File(dir_fus, ThermalMode+"_"+timeStamp+".png");

        // todo: change PDF to - PNG, BMP, TIFF. DONE!
        // create stream to the path and save the image
        try {
            // create the stream
            outputStream = new FileOutputStream(file_dc);
            // save image
            dcSave.compress(Bitmap.CompressFormat.PNG, 100, outputStream);

            // for fus image
            outputStream = new FileOutputStream(file_fus);
            fusSave.compress(Bitmap.CompressFormat.PNG, 100, outputStream);


            // success saving all the image
            Toast.makeText(getApplicationContext(), "save in - " + dir, Toast.LENGTH_SHORT).show();
            // Toast.makeText(getApplicationContext(), "bitmap size:" + dcSave.getByteCount(), Toast.LENGTH_SHORT).show();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Toast.makeText(getApplicationContext(), "can't access file stream", Toast.LENGTH_LONG).show();
        } finally {
            // close stream
            if(outputStream != null) {
                try {
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void open_activity_changeMode(View v) {
        Intent intent = new Intent(this, ChangeThermalMode.class);
        intent.putExtra("ThermalMode", ThermalMode);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == 1) {
            if(resultCode == RESULT_OK) {
                ThermalMode = data.getStringExtra("ThermalMode");
                thermalModeText.setText(ThermalMode);
            }
        }
    }
}