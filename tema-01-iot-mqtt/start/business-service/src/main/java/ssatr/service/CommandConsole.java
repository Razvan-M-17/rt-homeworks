package ssatr.service;

import java.util.Scanner;

/**
 * Consola operatorului: citește comenzi de la tastatură.
 *
 *   show                               afișează dashboard-ul
 *   <deviceId> <COMANDA> [parametru]   trimite o comandă unui device, de exemplu
 *                                      device-1 SET_INTERVAL 1000
 *   help                               afișează comenzile
 *   exit                               oprește serviciul
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        printHelp();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            } else if (line.equals("exit")) {
                break;
            } else if (line.equals("help")) {
                printHelp();
            } else if (line.equals("show")) {
                dashboard.print();
            } else {
                sendCommand(line);
            }
        }
    }

    private void sendCommand(String line) {
        String[] parts = line.split("\\s+", 2);

        if (parts.length != 2) {
            System.out.println(
                    "Format invalid. Exemplu: "
                            + "device-1 SET_INTERVAL 1000"
            );
            return;
        }

        String deviceId = parts[0];
        String command = parts[1].trim();

        if (deviceId.isBlank() || command.isBlank()) {
            System.out.println("Device ID sau comandă goală.");
            return;
        }

        String[] commandParts = command.split("\\s+");

        String commandName = commandParts[0];

        boolean valid = switch (commandName) {
            case "RESET", "STATUS" ->
                    commandParts.length == 1;

            case "SET_INTERVAL", "SET_THRESHOLD" ->
                    commandParts.length == 2;

            default -> false;
        };

        if (!valid) {
            System.out.println("Comandă invalidă: " + command);
            return;
        }

        mqtt.publish(
                Config.commandTopic(deviceId),
                command
        );

        System.out.println(
                "Comandă trimisă către " + deviceId + ": " + command
        );
    }

    private void printHelp() {
        System.out.println("Comenzi: show | <deviceId> SET_INTERVAL <ms> | <deviceId> SET_THRESHOLD <valoare>");
        System.out.println("         <deviceId> RESET | <deviceId> STATUS | help | exit");
    }
}
