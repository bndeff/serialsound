package app.serialsound;

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.billthefarmer.mididriver.MidiDriver;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    /*
    language used:
    "command" is a message received over serial, corresponding to a physical button press or release
    "tag" is either a midi note (before applying a modifier) or a modifier
    "note" is a midi note number, e.g. 60 = middle C = "C4"
    */

    private Map<String, String> mapping;        // e.g. "button 1 pressed" to midi note 60 (presses only)
    private Map<String, String> pair;           // e.g. "button 1 pressed" to "button 1 released" and vice versa
    private Map<String, Boolean> pressed;       // e.g. "button 1 pressed" to current state of button 1 (presses only)
    private Map<String, Integer> playing;       // e.g. "button 1 pressed" currently responsible for midi note 61
                                                    // because sharp modifier was held down while pressing it
    private Map<Integer, Set<String>> owners;   // e.g. midi note 61 should be stopped if buttons 1 & 2 are both
                                                    // released, as button 1 also plays midi node 61 right now
    private Set<Integer> sustained;             // unowned notes playing only because of sustain
    private Set<String> sustainers;             // sustain should last until these buttons are released
    private int offset;                         // modifier to be added to newly played notes
                                                    // e.g. sharp = 1, flat = -1, octave up = 12, down = -12

    private String internalPress;               // when not in learning mode, software buttons also simulate
    private String internalRelease;                 // serial commands; these are the prefixes they use

    private UsbManager usbManager;
    private List<String> usbPorts;
    private Map<String, UsbSerialPort> usbPortMap;
    private final String[] baudList = new String[] {
            "300", "1200", "2400", "4800", "9600", "19200", "38400", "57600", "74880",
            "115200", "230400", "250000", "500000", "1000000", "2000000"
    };
    private int baudRate;
    private boolean connected;
    private UsbSerialPort port;
    private SerialInputOutputManager usbIoManager;

    private MidiDriver midiDriver;
    private BroadcastReceiver broadcastReceiver;

    private AppCompatButton learningButton;
    private int learningState;
    private ColorStateList restoreColor;
    private String pressString;
    private boolean mappingUnsaved;

    static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    static final int defaultBaudRate = 9600;

    private void initData() {
        mapping = new HashMap<>();
        pair = new HashMap<>();
        pressed = new HashMap<>();
        playing = new HashMap<>();
        owners = new HashMap<>();
        sustained = new LinkedHashSet<>();
        sustainers = new LinkedHashSet<>();
        offset = 0;
        internalPress = "@press@";
        internalRelease = "@release@";
        baudRate = defaultBaudRate;
        connected = false;
        port = null;
        learningButton = null;
        learningState = 0;
        restoreColor = null;
        pressString = null;
        mappingUnsaved = false;
    }

    private void initMidi() {
        midiDriver = new MidiDriver();
    }

    private void startMidi() {
        midiDriver.start();
        midiDriver.setVolume(75);
    }

    private void stopMidi() {
        midiDriver.stop();
    }

    private void midiNoteOff(int note) {
        byte[] event = new byte[3];
        event[0] = (byte) 0x80;
        event[1] = (byte) note;
        midiDriver.write(event);
    }

    private void midiNoteOn(int note) {
        byte[] event = new byte[3];
        event[0] = (byte) 0x90;
        event[1] = (byte) note;
        event[2] = (byte) 0x7f;  // velocity
        midiDriver.write(event);
    }

    private void debugOffset() {
        Log.d("offset", String.valueOf(offset));
    }

    private void handlePress(String command) {
        if(pressed.containsKey(command)) {
            Boolean isPressed = pressed.get(command);
            if(isPressed != null && isPressed) return;
        }
        if(!mapping.containsKey(command)) return;
        String tag = mapping.get(command);
        if(tag == null) return;
        pressed.put(command, true);
        if(tag.matches("\\d+")) {
            int note = Integer.parseInt(tag) + offset;
            playing.put(command, note);
            Set<String> noteOwners = owners.get(note);
            if(noteOwners == null) {
                noteOwners = new LinkedHashSet<>();
                owners.put(note, noteOwners);
            }
            if(noteOwners.isEmpty()) {
                midiNoteOn(note);
            }
            noteOwners.add(command);
        }
        else if(tag.equals("sharp")) {
            ++offset;
            debugOffset();
        }
        else if(tag.equals("flat")) {
            --offset;
            debugOffset();
        }
        else if(tag.equals("up")) {
            offset += 12;
            debugOffset();
        }
        else if((tag.equals("down"))) {
            offset -= 12;
            debugOffset();
        }
        else if((tag.equals("sustain"))) {
            sustainers.add(command);
        }
    }

    private void handleRelease(String command) {
        // the argument is already the press command corresponding to the release
        if(!pressed.containsKey(command)) return;
        Boolean isPressed = pressed.get(command);
        if(isPressed == null || !isPressed) return;
        if(!mapping.containsKey(command)) return;
        String tag = mapping.get(command);
        if(tag == null) return;
        pressed.put(command, false);
        if(tag.matches("\\d+")) {
            if(!playing.containsKey(command)) return;
            Integer noteInt = playing.get(command);
            if(noteInt == null) return;
            int note = noteInt;
            playing.remove(command);
            Set<String> noteOwners = owners.get(note);
            if(noteOwners == null) return;
            noteOwners.remove(command);
            if(noteOwners.isEmpty()) {
                handleOrphanedNote(note);
            }
        }
        else if(tag.equals("sharp")) {
            --offset;
            debugOffset();
        }
        else if(tag.equals("flat")) {
            ++offset;
            debugOffset();
        }
        else if(tag.equals("up")) {
            offset -= 12;
            debugOffset();
        }
        else if(tag.equals("down")) {
            offset += 12;
            debugOffset();
        }
        else if(tag.equals("sustain")) {
            sustainers.remove(command);
            if(sustainers.isEmpty()) {
                handleStopSustain();
            }
        }
    }

    private void handleOrphanedNote(int note) {
        if(sustainers.isEmpty()) {
            midiNoteOff(note);
        } else {
            sustained.add(note);
        }
    }

    private void handleStopSustain() {
        for(Integer noteInt: sustained) {
            int note = noteInt;
            midiNoteOff(note);
        }
        sustained.clear();
    }

    private void handleCommand(String command) {
        Log.d("command", command);
        if(mapping.containsKey(command)) {
            handlePress(command);
        } else {
            if(pair.containsKey(command)) {
                String pressCommand = pair.get(command);
                handleRelease(pressCommand);
            }
        }
    }

    private void stopUnfinishedLearning() {
        if(learningState == 2) {
            handleRelease(pressString);
            unregisterCommand(pressString, false);
        }
        ViewCompat.setBackgroundTintList(learningButton, restoreColor);
        learningState = 0;
        learningButton = null;
    }

    private void handleInternalButton(View v, boolean newState) {
        AppCompatButton btn = (AppCompatButton) v;
        String tag = (String) btn.getTag();
        CheckBox learnCheckbox = findViewById(R.id.LearnCheckbox);
        boolean learn = learnCheckbox.isChecked();
        if (newState) {
            if(learn) {
                if (learningState > 0) stopUnfinishedLearning();
                learningButton = btn;
                learningState = 1;
                restoreColor = ViewCompat.getBackgroundTintList(btn);
                int clr = ContextCompat.getColor(this, R.color.colorWaitingPress);
                ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(clr));
            }
            handleCommand(internalPress + tag);
        } else {
            handleCommand(internalRelease + tag);
        }
    }

    private void processSerialCommand(String cmd) {
        TextView statusText = findViewById(R.id.StatusText);
        statusText.setText(String.format("Received \"%s\"", cmd));
        switch(learningState) {
            case 1:
                registerPressCommand(cmd, (String) learningButton.getTag());
                pressString = cmd;
                int clr = ContextCompat.getColor(this, R.color.colorWaitingRelease);
                ViewCompat.setBackgroundTintList(learningButton, ColorStateList.valueOf(clr));
                learningState = 2;
                handleCommand(cmd);
                break;
            case 2:
                if(!cmd.equals(pressString)) {
                    registerReleaseCommand(cmd, pressString);
                    ViewCompat.setBackgroundTintList(learningButton, restoreColor);
                    learningState = 0;
                    learningButton = null;
                    restoreColor = null;
                    pressString = null;
                    handleCommand(cmd);
                }
                break;
            default:
                handleCommand(cmd);
        }
    }

    private void processSerialData(byte[] data) {
        String s = new String(data);
        for(String line : s.split("\n")) {
            String cmd = line.trim();
            if(!cmd.isEmpty()) {
                processSerialCommand(cmd);
            }
        }
    }

    private void unregisterCommand(String command, boolean considerPair) {
        mappingUnsaved = true;
        if(considerPair && pair.containsKey(command)) {
            unregisterCommand(pair.get(command), false);
        }
        if(mapping.containsKey(command)) {
            if(pressed.containsKey(command)) {
                Boolean isPressed = pressed.get(command);
                if(isPressed != null && isPressed) {
                    handleRelease(command);
                    pressed.remove(command);
                }
            }
            mapping.remove(command);
        }
        pair.remove(command);
        if(playing.containsKey(command)) {
            Integer noteInt = playing.get(command);
            if(noteInt != null) {
                int note = noteInt;
                if(owners.containsKey(noteInt)) {
                    Set<String> noteOwners = owners.get(noteInt);
                    if (noteOwners != null) {
                        noteOwners.remove(command);
                        if (noteOwners.isEmpty()) {
                            handleOrphanedNote(note);
                        }
                    }
                }
            }
        }
        playing.remove(command);
        if(sustainers.contains(command)) {
            sustainers.remove(command);
            if(sustainers.isEmpty()) {
                handleStopSustain();
            }
        }
    }

    private void registerPressCommand(String cmd, String tag) {
        unregisterCommand(cmd, true);
        mapping.put(cmd, tag);
        pressed.put(cmd, false);
        playing.remove(cmd);
    }

    private void registerReleaseCommand(String cmd, String pressCmd) {
        unregisterCommand(cmd, true);
        mapping.remove(cmd);     // these removes are superfluous, but let's be extra careful
        pair.put(pressCmd, cmd);
        pair.put(cmd, pressCmd);
        pressed.remove(cmd);
        playing.remove(cmd);
    }

    private void registerCommandPair(String pressCmd, String releaseCmd, String tag) {
        registerPressCommand(pressCmd, tag);
        registerReleaseCommand(releaseCmd, pressCmd);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void registerInternalButton(AppCompatButton btn, String tag) {
        registerCommandPair(internalPress + tag, internalRelease + tag, tag);
        btn.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if(action == MotionEvent.ACTION_DOWN) {
                handleInternalButton(v, true);
            } else if(action == MotionEvent.ACTION_UP){
                handleInternalButton(v, false);
            }
            return true;
        });
    }

    private void initUsb() {
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        usbPorts = new ArrayList<>();
        usbPortMap = new LinkedHashMap<>();
    }

    private void refreshDeviceList() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        usbPorts.clear();
        usbPortMap.clear();
        for(UsbSerialDriver d: drivers) {
            for(UsbSerialPort p: d.getPorts()) {
                UsbDevice dev = d.getDevice();
                String devClass = p.getClass().getName().replaceFirst("^.*\\$", "").replaceFirst("SerialPort$", "");
                String devPath = dev.getDeviceName().replaceFirst("^/dev/bus/usb/", "") + "/" + p.getPortNumber();
                String devId = String.format("%04x:%04x", dev.getVendorId(), dev.getProductId());
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    devId = String.format("\"%s\" %s", dev.getProductName(), devId);
                }
                String desc = String.format("%s @ %s %s", devClass, devPath, devId);
                usbPorts.add(desc);
                usbPortMap.put(desc, p);
            }
        }
        Spinner deviceSpinner = findViewById(R.id.DeviceSpinner);
        AppCompatButton connectButton = findViewById(R.id.ConnectButton);
        ArrayAdapter<String> adapter;
        if(usbPorts.isEmpty()) {
            String[] noDevice = new String[]{"No device found"};
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, noDevice);
            deviceSpinner.setEnabled(false);
            connectButton.setEnabled(false);
        } else {
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, usbPorts);
            deviceSpinner.setEnabled(true);
            connectButton.setEnabled(true);
        }
        deviceSpinner.setAdapter(adapter);
    }

    private void disconnectFromDevice() {
        if(!connected) return;
        if(port == null) return;
        try {
            port.close();
            if(usbIoManager != null) {
                usbIoManager.stop();
                usbIoManager = null;
            }
        } catch (IOException e) {
            Toast.makeText(this, "Serial communication error", Toast.LENGTH_SHORT).show();
            return;
        }
        connected = false;
        TextView statusText = findViewById(R.id.StatusText);
        statusText.setText(R.string.status_disconnected);
        Spinner baudSpinner = findViewById(R.id.BaudSpinner);
        baudSpinner.setEnabled(true);
        AppCompatButton refreshButton = findViewById(R.id.RefreshButton);
        refreshButton.setEnabled(true);
        AppCompatButton connectButton = findViewById(R.id.ConnectButton);
        connectButton.setText(R.string.button_connect);
        refreshDeviceList();
    }

    private void loadBaudRate() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        baudRate = sp.getInt("baudrate", defaultBaudRate);
    }

    private void saveBaudRate() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        int savedBaudRate = sp.getInt("baudrate", defaultBaudRate);
        if(savedBaudRate == baudRate) return;
        SharedPreferences.Editor spe = sp.edit();
        spe.putInt("baudrate", baudRate);
        spe.apply();
    }

    private void connectToDevice(Boolean askForPermission) {
        if(connected) return;
        Spinner deviceSpinner = findViewById(R.id.DeviceSpinner);
        String portName = (String) deviceSpinner.getSelectedItem();
        Spinner baudSpinner = findViewById(R.id.BaudSpinner);
        baudRate = Integer.parseInt((String) baudSpinner.getSelectedItem());
        saveBaudRate();
        port = usbPortMap.get(portName);
        if(port == null) return;
        UsbDevice dev = port.getDevice();
        UsbDeviceConnection connection = usbManager.openDevice(dev);
        if(connection == null) {
            if(!usbManager.hasPermission(dev)) {
                if(askForPermission) {
                    registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));
                    int flags = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        flags |= FLAG_IMMUTABLE;
                    }
                    PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this,
                            0, new Intent(INTENT_ACTION_GRANT_USB), flags);
                    usbManager.requestPermission(dev, usbPermissionIntent);
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        try {
            port.open(connection);
            port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usbIoManager = new SerialInputOutputManager(port, this);
            Executors.newSingleThreadExecutor().submit(usbIoManager);
        } catch (IOException e) {
            Toast.makeText(this, "Serial communication error", Toast.LENGTH_SHORT).show();
            return;
        }
        connected = true;
        TextView statusText = findViewById(R.id.StatusText);
        statusText.setText(R.string.status_connected);
        deviceSpinner.setEnabled(false);
        baudSpinner.setEnabled(false);
        AppCompatButton refreshButton = findViewById(R.id.RefreshButton);
        refreshButton.setEnabled(false);
        AppCompatButton connectButton = findViewById(R.id.ConnectButton);
        connectButton.setText(R.string.button_disconnect);
    }

    private void saveMapping() {
        if(mappingUnsaved) {
            String mappingString = (new JSONObject(mapping)).toString();
            String pairString = (new JSONObject(pair)).toString();
            SharedPreferences sp = getPreferences(MODE_PRIVATE);
            SharedPreferences.Editor spe = sp.edit();
            spe.putString("mapping", mappingString);
            spe.putString("pair", pairString);
            spe.apply();
            mappingUnsaved = false;
        }
    }

    private void loadJsonObjectIntoMap(JSONObject in, Map<String, String> out) throws JSONException {
        out.clear();
        Iterator<String> iter = in.keys();
        while(iter.hasNext()) {
            String key = iter.next();
            String value = (String) in.get(key);
            out.put(key, value);
        }
    }

    private void clearMapping() {
        mapping.clear();
        pair.clear();
        mappingUnsaved = false;
    }

    private void loadMapping() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        String mappingString = sp.getString("mapping", null);
        if(mappingString != null) {
            try {
                loadJsonObjectIntoMap(new JSONObject(mappingString), mapping);
            } catch (JSONException e) {
                clearMapping();
                return;
            }
        } else {
            clearMapping();
            return;
        }
        String pairString = sp.getString("pair", null);
        if(pairString != null) {
            try {
                loadJsonObjectIntoMap(new JSONObject(pairString), pair);
            } catch (JSONException e) {
                clearMapping();
                return;
            }
        } else {
            clearMapping();
            return;
        }
        mappingUnsaved = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initUsb();
        initMidi();
        initData();
        loadMapping();
        setContentView(R.layout.activity_main);

        TableLayout tl = findViewById(R.id.NotesTable);
        for(int i=0; i<8; ++i) {
            TableRow tr = new TableRow(this);
            TableLayout.LayoutParams tlp = new TableLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMargins(0, 0, 0,0);
            tr.setLayoutParams(tlp);
            for(int j=0; j<9; ++j) {
                AppCompatButton btn = new AppCompatButton(this);
                TableRow.LayoutParams rlp = new TableRow.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1
                );
                rlp.setMargins(0, 0, 0, 0);
                btn.setLayoutParams(rlp);
                btn.setSingleLine(true);
                btn.setMinWidth(0);
                btn.setMinHeight(0);
                int midiNote = 33 + 9*i + j;
                int octave = midiNote/12 - 1;
                char noteChar = "C.D.EF.G.A.B".charAt(midiNote%12);
                int clr;
                boolean hl = midiNote >= 60 && midiNote <= 72;
                if(noteChar != '.') {
                    btn.setText(String.format(Locale.ROOT, "%c%d", noteChar, octave));
                    clr = ContextCompat.getColor(this, hl ? R.color.colorWhiteHighlight : R.color.colorWhiteKey);
                } else {
                    clr = ContextCompat.getColor(this, hl ? R.color.colorBlackHighlight : R.color.colorBlackKey);
                }
                ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(clr));
                String tag = String.valueOf(midiNote);
                btn.setTag(tag);
                registerInternalButton(btn, tag);
                tr.addView(btn);
            }
            tl.addView(tr);
        }

        int clr = ContextCompat.getColor(this, R.color.colorDefaultButton);
        ColorStateList defaultTint = ColorStateList.valueOf(clr);
        LinearLayout mb = findViewById(R.id.ModifierButtons);
        int mbCount = mb.getChildCount();
        for(int i=0; i<mbCount; ++i) {
            AppCompatButton mod = (AppCompatButton) mb.getChildAt(i);
            String tag = (String) mod.getTag();
            registerInternalButton(mod, tag);
            ViewCompat.setBackgroundTintList(mod, defaultTint);
        }
        mappingUnsaved = false;

        loadBaudRate();
        Spinner baudSpinner = findViewById(R.id.BaudSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, baudList);
        baudSpinner.setAdapter(adapter);
        String baudStr = String.valueOf(baudRate);
        for(int i=0; i<baudList.length; ++i) {
            if(baudList[i].equals(baudStr)) {
                baudSpinner.setSelection(i);
            }
        }

        AppCompatButton refreshButton = findViewById(R.id.RefreshButton);
        //ViewCompat.setBackgroundTintList(refreshButton, defaultTint);
        refreshButton.setOnClickListener(v -> refreshDeviceList());
        refreshDeviceList();

        AppCompatButton connectButton = findViewById(R.id.ConnectButton);
        //ViewCompat.setBackgroundTintList(connectButton, defaultTint);
        connectButton.setOnClickListener(v -> {
            if(connected) {
                disconnectFromDevice();
            } else {
                connectToDevice(true);
            }
        });

        CheckBox learnCheckbox = findViewById(R.id.LearnCheckbox);
        learnCheckbox.setOnClickListener(v -> {
            CheckBox cb = (CheckBox) v;
            boolean newState = cb.isChecked();
            if(!newState && learningState > 0) {
                stopUnfinishedLearning();
            }
            TextView usageText = findViewById(R.id.UsageText);
            usageText.setVisibility(newState ? View.VISIBLE : View.GONE);
        });
        TextView usageText = findViewById(R.id.UsageText);
        usageText.setVisibility(View.GONE);

        CheckBox awakeCheckbox = findViewById(R.id.AwakeCheckbox);
        awakeCheckbox.setOnClickListener(v -> {
            CheckBox cb = (CheckBox) v;
            if(cb.isChecked()) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if(action == null) return;
                if(action.equals(INTENT_ACTION_GRANT_USB)) {
                    unregisterReceiver(broadcastReceiver);
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connectToDevice(granted);
                }
            }
        };

    }

    @Override
    public void onNewData(final byte[] data) {
        runOnUiThread(() -> MainActivity.this.processSerialData(data));
    }

    @Override
    public void onRunError(Exception e) {
        runOnUiThread(() -> {
            if(connected) {
                Toast.makeText(MainActivity.this, "Connection lost", Toast.LENGTH_SHORT).show();
                disconnectFromDevice();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startMidi();
    }

    @Override
    protected void onPause() {
        stopMidi();
        saveMapping();
        super.onPause();
    }
}
