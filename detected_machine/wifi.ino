#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_MLX90614.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- [1] XIAO ESP32C6 핀 설정 ---
#define SDA_PIN D4   // D4
#define SCL_PIN D5   // D5
#define MPU_INT_PIN D0  // D0 (GPIO 0)

// --- [2] 낙상 감지 설정 ---
const float IMPACT_THRESHOLD = 30.0; 
const float TILT_THRESHOLD = 45.0;   
const unsigned long FALL_CHECK_DELAY = 3000; 
const float MAX_NOISE_LIMIT = 100.0; 

// 변수들
volatile bool mpuInterrupt = false;
int fallStatus = 0; 
bool isWaitingForStability = false; 
unsigned long impactTime = 0; 
unsigned long lastAnglePrintTime = 0;

int sendCounter = 0;
const int SEND_INTERVAL = 5; 

Adafruit_MPU6050 mpu;
Adafruit_MLX90614 mlx = Adafruit_MLX90614();

// BLE 설정 (Nordic UART 표준)
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" 
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLEServer* pServer = NULL;
BLECharacteristic* pTxCharacteristic = NULL;
BLECharacteristic* pRxCharacteristic = NULL;

bool deviceConnected = false;
bool oldDeviceConnected = false;
unsigned long connectionStartTime = 0;
const unsigned long WARMUP_TIME = 1500; 

void IRAM_ATTR onMPUDataReady() {
  mpuInterrupt = true;
}

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      connectionStartTime = millis();
      Serial.println(">> 📱 XIAO BLE 연결 성공!");
    };
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      pServer->startAdvertising(); 
    }
};

class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {}
};

// [복구 함수]
void tryReconnectSensors() {
    Serial.println("⚠️ 센서 복구 시도...");
    Wire.end(); delay(50); 
    Wire.begin(SDA_PIN, SCL_PIN); // XIAO 핀 지정
    Wire.setClock(100000); 

    if (mpu.begin()) {
        mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
        Wire.beginTransmission(0x68); Wire.write(0x38); Wire.write(0x01); Wire.endTransmission();
        Serial.println(">> ✅ MPU6050 복구 완료!");
    }
    mlx.begin();
}

bool checkSensorAlive() {
    Wire.beginTransmission(0x68);
    return (Wire.endTransmission() == 0);
}

void setup() {
  Serial.begin(115200);
  
  // XIAO D0 핀 설정
  pinMode(MPU_INT_PIN, INPUT_PULLUP); // 내부 풀업 사용 추천
  
  Wire.setTimeOut(50); 

  // I2C 시작 (핀 지정)
  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000); 

  if (!mpu.begin() || !mlx.begin()) {
    Serial.println("❌ 센서 인식 실패 (선 확인)");
  } else {
    Serial.println("✅ 센서 정상 인식");
  }

  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ); 
  Wire.beginTransmission(0x68); Wire.write(0x38); Wire.write(0x01); Wire.endTransmission();
  attachInterrupt(digitalPinToInterrupt(MPU_INT_PIN), onMPUDataReady, RISING);

  // BLE 초기화
  BLEDevice::init("XIAO_C6_Fall_Sensor");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  pRxCharacteristic = pService->createCharacteristic(CHARACTERISTIC_UUID_RX, BLECharacteristic::PROPERTY_WRITE);
  pRxCharacteristic->setCallbacks(new MyCallbacks());

  pTxCharacteristic = pService->createCharacteristic(CHARACTERISTIC_UUID_TX, BLECharacteristic::PROPERTY_NOTIFY);
  pTxCharacteristic->addDescriptor(new BLE2902());

  pService->start();
  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::getAdvertising()->start();
  
  Serial.println("🚀 XIAO 시스템 가동");
}

void loop() {
  if (mpuInterrupt) {
    mpuInterrupt = false;

    sensors_event_t a, g, temp;
    if (!mpu.getEvent(&a, &g, &temp)) {
      tryReconnectSensors(); 
      return; 
    }

    float objTemp = mlx.readObjectTempC();
    float ambTemp = mlx.readAmbientTempC();
    if (isnan(objTemp)) objTemp = 0.0;
    if (isnan(ambTemp)) ambTemp = 0.0;

    // --- 낙상 알고리즘 ---
    float svm = sqrt(pow(a.acceleration.x, 2) + pow(a.acceleration.y, 2) + pow(a.acceleration.z, 2));
    float angleX = atan2(a.acceleration.y, a.acceleration.z) * 180 / PI;
    float angleY = atan2(-a.acceleration.x, sqrt(pow(a.acceleration.y, 2) + pow(a.acceleration.z, 2))) * 180 / PI;
    float totalTilt = max(abs(angleX), abs(angleY)); 

    if (!isWaitingForStability) {
      if (svm > IMPACT_THRESHOLD) {
        if (svm > MAX_NOISE_LIMIT) {
           tryReconnectSensors();
        }
        else if (checkSensorAlive()) {
           isWaitingForStability = true;
           impactTime = millis();
           Serial.println("⚠️ 충격 감지! (3초 대기...)"); 
        } 
      }
      if (fallStatus == 1 && totalTilt < TILT_THRESHOLD) {
         fallStatus = 0;
         Serial.println(" -> ✅ 회복");
      }
    } else {
      // 대기 중 각도 출력
      if (millis() - lastAnglePrintTime >= 500) {
        lastAnglePrintTime = millis();
        Serial.print("⏳ AngleX: "); Serial.print(abs(angleX));
        Serial.print(" | AngleY: "); Serial.println(abs(angleY));
      }
      
      if ((millis() - impactTime) > FALL_CHECK_DELAY) {
        isWaitingForStability = false; 
        if (totalTilt > TILT_THRESHOLD) {
          fallStatus = 1; 
          Serial.println("🚨 낙상 확정!");
        } else {
          fallStatus = 0; 
          Serial.println("👌 정상");
        }
      }
    }

    sendCounter++;
    if (deviceConnected) {
      if ((millis() - connectionStartTime) > WARMUP_TIME) {
         if (sendCounter >= SEND_INTERVAL) {
            sendCounter = 0;
            String allData = String(a.acceleration.x, 2) + "," + String(a.acceleration.y, 2) + "," + String(a.acceleration.z, 2) + "," +
                             String(g.gyro.x, 2) + "," + String(g.gyro.y, 2) + "," + String(g.gyro.z, 2) + "," +
                             String(objTemp, 2) + "," + String(ambTemp, 2) + "," + String(fallStatus);
            pTxCharacteristic->setValue(allData.c_str());
            pTxCharacteristic->notify();
            Serial.println(allData);
         }
      }
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