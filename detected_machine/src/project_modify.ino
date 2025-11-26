#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_MLX90614.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- [1] 핀 설정 ---
#define MPU_INT_PIN 23 

volatile bool mpuInterrupt = false;

Adafruit_MPU6050 mpu;
Adafruit_MLX90614 mlx = Adafruit_MLX90614();

// --- [2] BLE 설정 (채널 하나로 통합) ---
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_DATA "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // 통합 데이터 채널

BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristicData = NULL; // 통합된 특성 하나만 사용
bool deviceConnected = false;
bool oldDeviceConnected = false;

// --- [3] 인터럽트 서비스 루틴 ---
void IRAM_ATTR onMPUDataReady() {
  mpuInterrupt = true;
}

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println(">> 📱 스마트폰 연결 성공!");
    };
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">> 📱 연결 끊김.");
    }
};

void setup() {
  Serial.begin(115200);
  pinMode(MPU_INT_PIN, INPUT);

  // 센서 초기화
  if (!mpu.begin()) {
    Serial.println("❌ MPU6050 센서 없음!");
    while (1);
  }
  if (!mlx.begin()) {
    Serial.println("❌ MLX90614 센서 없음!");
    while (1);
  }
  Serial.println("✅ 모든 센서 정상 인식!");

  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ); 
  Wire.beginTransmission(0x68);
  Wire.write(0x38);
  Wire.write(0x01);
  Wire.endTransmission();
  attachInterrupt(digitalPinToInterrupt(MPU_INT_PIN), onMPUDataReady, RISING);

  // BLE 초기화
  BLEDevice::init("ESP32_Sensor_Module");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // 하나의 특성(Characteristic)만 생성합니다.
  pCharacteristicData = pService->createCharacteristic(
                          CHARACTERISTIC_UUID_DATA,
                          BLECharacteristic::PROPERTY_READ |
                          BLECharacteristic::PROPERTY_NOTIFY
                        );
  pCharacteristicData->addDescriptor(new BLE2902());

  pService->start();
  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::getAdvertising()->start();
  
  Serial.println("🚀 시스템 준비 완료. 데이터 통합 전송 시작...");
}

void loop() {
  if (mpuInterrupt) {
    mpuInterrupt = false;

    // 1. 데이터 읽기
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    float objTemp = mlx.readObjectTempC();
    float ambTemp = mlx.readAmbientTempC();

    // 2. [핵심 변경] 데이터를 하나의 긴 문자열로 합치기
    // 순서: 가속도(X,Y,Z), 자이로(X,Y,Z), 물체온도, 주변온도 (총 8개 값)
    String allData = String(a.acceleration.x, 2) + "," + String(a.acceleration.y, 2) + "," + String(a.acceleration.z, 2) + "," +
                     String(g.gyro.x, 2) + "," + String(g.gyro.y, 2) + "," + String(g.gyro.z, 2) + "," +
                     String(objTemp, 2) + "," + String(ambTemp, 2);

    // 3. 시리얼 모니터 출력
    Serial.println(allData); 

    // 4. 블루투스 전송 (하나의 채널로 한 번만 전송!)
    if (deviceConnected) {
      pCharacteristicData->setValue(allData.c_str());
      pCharacteristicData->notify();
    }
  }

  // 연결 관리
  if (!deviceConnected && oldDeviceConnected) {
      delay(500); 
      pServer->startAdvertising();
      oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
  }
}