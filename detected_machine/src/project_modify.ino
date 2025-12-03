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

// --- [2] BLE 설정 ---
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" 
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer* pServer = NULL;
BLECharacteristic* pTxCharacteristic = NULL;
BLECharacteristic* pRxCharacteristic = NULL;

bool deviceConnected = false;
bool oldDeviceConnected = false;
unsigned long connectionStartTime = 0;
const unsigned long WARMUP_TIME = 1000; // 재연결 시 1초 대기

// [추가] 전송 속도 조절용 변수
int sendCounter = 0;
const int SEND_INTERVAL = 5; // 5번 측정할 때 1번만 전송 (속도 1/5로 낮춤)

// --- [3] 인터럽트 서비스 루틴 ---
void IRAM_ATTR onMPUDataReady() {
  mpuInterrupt = true;
}

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      connectionStartTime = millis();
      Serial.println(">> 📱 연결 성공! (안정화 대기 중...)");
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">> ❌ 연결 끊김");
      pServer->startAdvertising(); 
      Serial.println(">> 📡 재연결 대기 중...");
    }
};

class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {}
};

void setup() {
  Serial.begin(115200);
  pinMode(MPU_INT_PIN, INPUT);

  if (!mpu.begin()) {
    Serial.println("❌ MPU6050 센서 없음!");
    while (1);
  }
  if (!mlx.begin()) {
    Serial.println("❌ MLX90614 센서 없음!");
    while (1);
  }
  Serial.println("✅ 센서 정상 인식");

  // 샘플링 속도 조절 (너무 빠르면 낮춥니다)
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ); 
  
  Wire.beginTransmission(0x68);
  Wire.write(0x38);
  Wire.write(0x01);
  Wire.endTransmission();
  attachInterrupt(digitalPinToInterrupt(MPU_INT_PIN), onMPUDataReady, RISING);

  BLEDevice::init("ESP32_Sensor_Module");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  pRxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_RX,
                        BLECharacteristic::PROPERTY_WRITE
                      );
  pRxCharacteristic->setCallbacks(new MyCallbacks());

  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY 
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  pService->start();
  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::getAdvertising()->start();
  
  Serial.println("🚀 시스템 준비 완료");
}

void loop() {
  if (mpuInterrupt) {
    mpuInterrupt = false;

    // 1. 데이터 읽기 (센서는 항상 빠르게 읽음 - 낙상 감지 등을 위해)
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    float objTemp = mlx.readObjectTempC();
    float ambTemp = mlx.readAmbientTempC();

    // 2. 카운터 증가
    sendCounter++;

    // 3. 전송 조건: (연결됨) AND (1초 지남) AND (5번에 1번만 전송)
    if (deviceConnected) {
      if ((millis() - connectionStartTime) > WARMUP_TIME) {
         
         // [핵심] 5번 중 1번만 보냅니다 (속도 조절)
         if (sendCounter >= SEND_INTERVAL) {
            sendCounter = 0; // 카운터 초기화

            String allData = String(a.acceleration.x, 2) + "," + String(a.acceleration.y, 2) + "," + String(a.acceleration.z, 2) + "," +
                             String(g.gyro.x, 2) + "," + String(g.gyro.y, 2) + "," + String(g.gyro.z, 2) + "," +
                             String(objTemp, 2) + "," + String(ambTemp, 2);

            pTxCharacteristic->setValue(allData.c_str());
            pTxCharacteristic->notify();
         }
      }
    }
  }

  if (!deviceConnected && oldDeviceConnected) {
      delay(500); 
      pServer->startAdvertising();
      oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
  }
}
