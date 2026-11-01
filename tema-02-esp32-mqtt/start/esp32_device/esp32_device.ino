// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Așa cum este, schița se conectează la Wi-Fi și la broker și afișează pe
// Serial comenzile primite. Taskurile le scrieți voi: TODO 0 ... TODO 3.
// Exemplele de pornire sunt în laboratorul 0 din rt-labs-esp32c3; vezi
// exemple/README.md din folderul temei.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

#include <stdlib.h>
#include <string.h>

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.
float read_temperature(void) {
    return temperatureRead();
}

// Date comune
struct AlarmEvent {
    float temperature;
    float threshold;
};

struct CommandMessage {
    char text[COMMAND_MAX_LEN];
};

QueueHandle_t alarm_queue;
QueueHandle_t command_queue;

volatile uint32_t period_ms = PERIOD_MS_INITIAL;
volatile float threshold_c = THRESHOLD_INITIAL;

void publish_status(const char* text) {
    char message[160];
    snprintf(message, sizeof(message), "%s;%lld;%s",
             DEVICE_ID, (long long)epoch_ms(), text);
    mqtt_publish("status", message);
    Serial.printf("[status] %s\n", message);
}

// TODO 1: TASK PERIODIC, cu perioada T (inițial PERIOD_MS_INITIAL).
void periodic_task(void* arg) {
    (void)arg;
    TickType_t last_wake = xTaskGetTickCount();

    for (;;) {
        float temperature = read_temperature();
        float threshold = threshold_c;

        char message[96];
        snprintf(message, sizeof(message), "%s;%lld;%.2f",
                 DEVICE_ID, (long long)epoch_ms(), temperature);
        mqtt_publish("telemetry", message);
        Serial.printf("[periodic] %s (prag %.2f)\n", message, threshold);

        if (temperature > threshold) {
            AlarmEvent event;
            event.temperature = temperature;
            event.threshold = threshold;
            if (xQueueSend(alarm_queue, &event, 0) != pdTRUE) {
                Serial.println("[periodic] coada de alarme este plina");
            }
        }

        uint32_t period = period_ms;
        if (period == 0) {
            period = PERIOD_MS_INITIAL;
        }
        xTaskDelayUntil(&last_wake, pdMS_TO_TICKS(period));
    }
}

// TODO 2: TASK SPORADIC, tratează alarmele de temperatură.
void sporadic_task(void* arg) {
    (void)arg;
    uint32_t last_alarm_ms = 0;
    bool published = false;

    for (;;) {
        AlarmEvent event;
        if (xQueueReceive(alarm_queue, &event, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        uint32_t now = millis();
        uint32_t elapsed = now - last_alarm_ms;
        if (published && elapsed < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] respinsa, au trecut %lu ms\n",
                          (unsigned long)elapsed);
            continue;
        }

        char message[120];
        snprintf(message, sizeof(message), "%s;%lld;%.2f;%.2f",
                 DEVICE_ID, (long long)epoch_ms(),
                 event.temperature, event.threshold);
        mqtt_publish("alarm", message);
        last_alarm_ms = now;
        published = true;
        Serial.printf("[sporadic] %s\n", message);
    }
}

// TODO 3: TASK APERIODIC, execută comenzile primite de la business service.
void aperiodic_task(void* arg) {
    (void)arg;

    for (;;) {
        CommandMessage command;
        if (xQueueReceive(command_queue, &command, portMAX_DELAY) != pdTRUE) {
            continue;
        }

        char name[COMMAND_MAX_LEN];
        char value[COMMAND_MAX_LEN];
        char extra[8];
        name[0] = '\0';
        value[0] = '\0';
        extra[0] = '\0';

        int count = sscanf(command.text, "%63s %63s %7s", name, value, extra);
        if (count < 1) {
            publish_status("ERROR comanda goala");
            continue;
        }

        if (strcmp(name, "SET_INTERVAL") == 0) {
            char* end = NULL;
            unsigned long interval = strtoul(value, &end, 10);
            if (count != 2 || value[0] == '-' || end == value || *end != '\0' || interval == 0) {
                publish_status("ERROR format: SET_INTERVAL <ms>");
                continue;
            }
            period_ms = (uint32_t)interval;
            char reply[80];
            snprintf(reply, sizeof(reply), "OK SET_INTERVAL %lu", interval);
            publish_status(reply);

        } else if (strcmp(name, "SET_THRESHOLD") == 0) {
            char* end = NULL;
            float threshold = strtof(value, &end);
            if (count != 2 || end == value || *end != '\0' || threshold != threshold) {
                publish_status("ERROR format: SET_THRESHOLD <valoare>");
                continue;
            }
            threshold_c = threshold;
            char reply[80];
            snprintf(reply, sizeof(reply), "OK SET_THRESHOLD %.2f", threshold);
            publish_status(reply);

        } else if (strcmp(name, "RESET") == 0) {
            if (count != 1) {
                publish_status("ERROR format: RESET");
                continue;
            }
            period_ms = PERIOD_MS_INITIAL;
            threshold_c = THRESHOLD_INITIAL;
            publish_status("OK RESET");

        } else if (strcmp(name, "STATUS") == 0) {
            if (count != 1) {
                publish_status("ERROR format: STATUS");
                continue;
            }
            char reply[96];
            snprintf(reply, sizeof(reply), "OK STATUS interval=%lu threshold=%.2f",
                     (unsigned long)period_ms, threshold_c);
            publish_status(reply);

        } else {
            char reply[80];
            snprintf(reply, sizeof(reply), "ERROR comanda necunoscuta: %s", name);
            publish_status(reply);
        }
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);

    if (command_queue == NULL) {
        return;
    }

    CommandMessage command;
    memset(&command, 0, sizeof(command));
    strncpy(command.text, text, COMMAND_MAX_LEN - 1);

    if (xQueueSend(command_queue, &command, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi este plina");
    }
}

void setup() {
    Serial.begin(115200);
    // Fără așteptare la scriere: cu placa la USB, dar fără Serial Monitor deschis,
    // fiecare Serial.printf() ar aștepta până la 2 s să fie citit și ar întârzia
    // taskul care scrie. Așa, textul pe care nu-l citește nimeni se pierde.
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);  // așteptăm Serial Monitor, cel mult 3 s
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    alarm_queue = xQueueCreate(10, sizeof(AlarmEvent));
    command_queue = xQueueCreate(10, sizeof(CommandMessage));
    if (alarm_queue == NULL || command_queue == NULL) {
        Serial.println("nu am putut crea cozile");
        return;
    }

    mqtt_start();

    xTaskCreate(periodic_task, "periodic", 4096, NULL, 5, NULL);
    xTaskCreate(sporadic_task, "sporadic", 4096, NULL, 4, NULL);
    xTaskCreate(aperiodic_task, "aperiodic", 4096, NULL, 3, NULL);
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}
