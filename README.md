# DEAD PACKET SOCIETY — DPS Field Controller

**Android field controller for the Dead Packet Society wireless reconnaissance system.**

DPS Field Controller is the Android command, monitoring, mapping, and data-collection application used as the primary field interface for the Dead Packet Society hardware system.

The Android device communicates with the DPS controller hardware over **Bluetooth Low Energy (BLE)** while the controller manages communication with distributed ESP32-based field nodes.

---

## Overview

The DPS Field Controller is designed around a distributed field-scanning architecture:

```text
                 ┌─────────────────────────┐
                 │     Android Phone       │
                 │                         │
                 │   DPS Field Controller  │
                 └────────────┬────────────┘
                              │
                             BLE
                              │
                              ▼
                 ┌─────────────────────────┐
                 │      DPS Controller     │
                 │                         │
                 │   BLE ↔ Wi-Fi Gateway   │
                 └────────────┬────────────┘
                              │
                         Wi-Fi / TCP
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
          ESP32 Node       ESP32 Node       ESP32 Node
          DPS-01           DPS-02           DPS-03
              │
              └───────────────┬───────────────┘
                              ▼
                         ESP32 Node
                         DPS-04
```

The phone serves as the primary user interface and data-management device. The controller provides the wireless bridge between the phone and field nodes.

---

## Features

### Field Controller

* BLE connection to the DPS controller
* Automatic controller status monitoring
* Controller connection status
* Distributed node status monitoring
* Node online counter
* Start and stop scanning commands
* Default scan configuration
* Real-time communication monitoring

### Wireless Reconnaissance

The system is designed to collect observations from distributed ESP32 nodes, including wireless information such as:

* Wi-Fi networks
* Bluetooth devices
* Bluetooth Low Energy devices
* 2.4 GHz observations
* 5.6 GHz observations
* Cellular observations

The distributed architecture allows multiple nodes to collect observations simultaneously.

---

## Mapping

The application provides an OpenStreetMap-based field map using **OSMdroid**.

The map can display field positioning and controller/node-related locations without relying on Google Maps for the application's mapping layer.

---

## Data Collection

DPS Field Controller associates observations with field information such as:

* MAC address
* Device/network name
* Authentication information
* Channel
* Frequency
* RSSI
* First-seen time
* GPS latitude
* GPS longitude
* Altitude
* Location accuracy
* Additional observation metadata

The application maintains observation tracking during an active capture session and can save collected session data locally.

---

## Capture Sessions

A capture session can be saved from the application for later analysis or archival.

Session data can include the observations collected during the field operation along with associated GPS and timestamp information.

---

## Stealth Mode

The application includes a **STEALTH MODE** intended to provide a simplified field interface while maintaining the underlying capture operation.

The interface can display aggregate observation counts without requiring the operator to expose detailed observation information during field use.

---

## DPS Hardware

The Android application is designed to work with DPS controller hardware based on ESP32 platforms.

Current controller development includes hardware such as:

* Heltec WiFi LoRa 32 V3
* Waveshare ESP32-C5
* Generic ESP32-C5 controller hardware

Field nodes are based around ESP32 devices, including the XIAO ESP32-C5.

---

## Communication Architecture

The primary communication path is:

```text
Android
   │
   │ BLE
   ▼
DPS Controller
   │
   │ Wi-Fi / TCP
   ▼
DPS Field Nodes
```

The Android application communicates with the controller using the DPS BLE service and command/status characteristics.

The controller handles communication with the distributed field nodes rather than requiring the phone to maintain individual connections to every node.

---

## BLE Interface

The controller exposes a DPS BLE service containing characteristics for communication between the Android application and controller.

The Android application uses BLE for:

* Controller connection
* Command transmission
* Status notifications
* Controller/node state monitoring
* Field operation control

---

## Android Project

### Package

```text
com.deadpacketsociety.fieldcontroller
```

### Main Components

The application is primarily organized around:

```text
MainActivity.kt
BleController.kt
```

`MainActivity` provides the field interface and application logic.

`BleController` manages communication between the Android application and the DPS controller.

---

## Requirements

### Development

* Android Studio
* Android SDK
* Gradle
* Kotlin
* A compatible Android device

### Field Operation

A compatible Android phone with:

* Bluetooth Low Energy
* GPS/location services
* Android networking support

The application is designed for use with the DPS controller hardware.

---

## Building

Clone the repository:

```bash
git clone https://github.com/Dead-Packet-Society/DPS-Field-Controller.git
```

Open the project in **Android Studio**.

Allow Android Studio to synchronize the Gradle project and install any required SDK components.

Build the application using:

```text
Build → Make Project
```

or generate an APK using:

```text
Build → Build APK(s)
```

Generated build files are intentionally excluded from version control.

---

## Project Structure

```text
DPS-Field-Controller/
│
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/deadpacketsociety/
│   │       │       └── fieldcontroller/
│   │       │
│   │       └── res/
│   │
│   ├── .gitignore
│   └── ...
│
├── build.gradle
├── settings.gradle
├── gradle/
├── .gitignore
└── README.md
```

Build artifacts, Gradle caches, Android Studio configuration, local machine configuration, and generated APK files should not be committed to the repository.

---

## Related DPS Hardware

The Android application is one component of the larger **Dead Packet Society field communications and reconnaissance system**.

The overall system consists of:

* Android field controller
* DPS BLE/Wi-Fi controller
* Distributed ESP32 field nodes
* Wireless observation collection
* GPS-based field mapping
* Local session storage

The hardware and firmware components are maintained separately from the Android application.

---

## Development Philosophy

DPS is designed around a modular distributed architecture.

The Android application remains the primary field interface while dedicated ESP32 hardware performs distributed wireless operations.

This allows additional field nodes and specialized hardware to be added without requiring the Android device to directly manage every individual scanner.

---

## Project Status

DPS Field Controller is an active development project.

Hardware, firmware, communication protocols, and Android features may change as the DPS system evolves.

This repository represents the Android application portion of the project.

---

## Dead Packet Society

**DEAD PACKET SOCIETY**

**NIGHTSHADE**

Built for field communications, distributed wireless experimentation, and custom hardware development.

---
