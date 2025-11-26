#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_MLX90614.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- [1] 핀 및 변수 설정 ---
#define MPU_INT_PIN 23  // MPU6050 INT 핀을 ESP32 GPIO 23에 연결

// 인터럽트 상태를 저장할 깃발 (반드시 volatile 사용)
volatile bool mpuInterrupt = false;

Adafruit_MPU6050 mpu;
Adafruit_MLX90614 mlx = Adafruit_MLX90614();

// --- [2] BLE 설정 (기존과 동일) ---
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_MPU "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_MLX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristicMPU = NULL;
BLECharacteristic* pCharacteristicMLX = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// --- [3] 인터럽트 서비스 루틴 (ISR) ---
// 센서가 신호를 보내면 이 함수가 즉시 실행됩니다.
void IRAM_ATTR onMPUDataReady() {
  mpuInterrupt = true; // "데이터 왔어요!" 깃발 들기
}

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
    };
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
    }
};

void setup() {
  Serial.begin(115200);
  pinMode(MPU_INT_PIN, INPUT); // INT 핀을 입력으로 설정

  // 센서 초기화
  if (!mpu.begin()) {
    Serial.println("MPU6050 not found!");
    while (1);
  }
  if (!mlx.begin()) {
    Serial.println("MLX90614 not found!");
    while (1);
  }

  // --- [4] MPU6050 인터럽트 설정 (중요!) ---
  // 1. 센서의 샘플링 속도 설정 (예: 10Hz = 0.1초마다 데이터 생성)
  // 이 속도에 맞춰서 인터럽트가 발생합니다.
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ); 

  // 2. MPU6050 내부 레지스터를 직접 건드려서 "데이터 준비되면 알림(Data Ready Interrupt)" 켜기
  Wire.beginTransmission(0x68); // MPU6050 주소
  Wire.write(0x38);             // 인터럽트 활성화 레지스터 (INT_ENABLE)
  Wire.write(0x01);             // 0번 비트(DATA_RDY_EN)를 1로 설정
  Wire.endTransmission();

  // 3. ESP32에 인터럽트 연결
  // MPU_INT_PIN 신호가 RISING(LOW -> HIGH) 될 때 onMPUDataReady 함수 실행
  attachInterrupt(digitalPinToInterrupt(MPU_INT_PIN), onMPUDataReady, RISING);

  // BLE 초기화 (기존과 동일)
  BLEDevice::init("ESP32_Sensor_Module");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCharacteristicMPU = pService->createCharacteristic(CHARACTERISTIC_UUID_MPU, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pCharacteristicMPU->addDescriptor(new BLE2902());

  pCharacteristicMLX = pService->createCharacteristic(CHARACTERISTIC_UUID_MLX, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pCharacteristicMLX->addDescriptor(new BLE2902());

  pService->start();
  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::getAdvertising()->start();
  
  Serial.println("Ready! Waiting for interrupt...");
}

void loop() {
  // --- [5] 루프 로직 변경 ---
  // delay()를 쓰지 않고, 깃발(mpuInterrupt)이 올라갔는지 확인합니다.
  
  if (deviceConnected && mpuInterrupt) {
    // 1. 깃발 내리기 (가장 먼저!)
    mpuInterrupt = false;

    // 2. 데이터 읽기
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp); // 데이터를 읽으면 MPU6050이 자동으로 INT핀을 초기화함

    float objTemp = mlx.readObjectTempC();
    float ambTemp = mlx.readAmbientTempC();

    // 3. 데이터 전송 (기존과 동일)
    String mpuStr = String(a.acceleration.x, 2) + "," + String(a.acceleration.y, 2) + "," + String(a.acceleration.z, 2) + "," +
                    String(g.gyro.x, 2) + "," + String(g.gyro.y, 2) + "," + String(g.gyro.z, 2);
    String mlxStr = String(objTemp, 2) + "," + String(ambTemp, 2);

    pCharacteristicMPU->setValue(mpuStr.c_str());
    pCharacteristicMPU->notify();

    pCharacteristicMLX->setValue(mlxStr.c_str());
    pCharacteristicMLX->notify();
    
    // Serial.println("Data Sent via Interrupt!"); 
  }

  // 연결 재시도 로직 (기존과 동일)
  if (!deviceConnected && oldDeviceConnected) {
      delay(500); 
      pServer->startAdvertising();
      oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
  }
}