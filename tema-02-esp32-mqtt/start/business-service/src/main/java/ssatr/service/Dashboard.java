package ssatr.service;

import java.util.LinkedHashMap;
import java.util.Map;

public class Dashboard {

    private final Map<String, DeviceState> devices = new LinkedHashMap<>();

    public synchronized void updateTelemetry(String deviceId, long timestamp, double temperature) {
        DeviceState state = devices.computeIfAbsent(deviceId, id -> new DeviceState());
        state.lastTemperature = temperature;
        state.lastMessageTime = timestamp;
    }

    public synchronized void updateAlarm(String deviceId, long timestamp, double temperature) {
        DeviceState state = devices.computeIfAbsent(deviceId, id -> new DeviceState());
        state.lastTemperature = temperature;
        state.lastMessageTime = timestamp;
        state.alarmCount++;
    }

    public synchronized void updateStatus(String deviceId, long timestamp) {
        DeviceState state = devices.computeIfAbsent(deviceId, id -> new DeviceState());
        state.lastMessageTime = timestamp;
    }

    public synchronized void print() {
        System.out.printf("%-16s %-12s %-18s %-8s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");
        if (devices.isEmpty()) {
            System.out.println("Nu exista dispozitive.");
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            DeviceState state = entry.getValue();
            long ageSeconds = (now - state.lastMessageTime) / 1000;
            System.out.printf("%-16s %-12.2f %-18s %-8d%n",
                    entry.getKey(), state.lastTemperature, ageSeconds + " secunde", state.alarmCount);
        }
    }

    private static class DeviceState {
        double lastTemperature = 0.0;
        long lastMessageTime = 0L;
        int alarmCount = 0;
    }
}