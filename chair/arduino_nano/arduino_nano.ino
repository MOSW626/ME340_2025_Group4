#define FAN_top 5
#define FAN_1 6
#define FAN_2 7
#define peltier 8

void setup()
{
  pinMode(FAN_top, OUTPUT);
  pinMode(FAN_1, OUTPUT);
  pinMode(FAN_2, OUTPUT);
  pinMode(peltier, OUTPUT);

  Serial.begin(9600);
}

void loop()
{
  int val = analogRead(A5)/4;
  Serial.println(val);

  if(val < 20)
  {
    digitalWrite(FAN_top, LOW);
    digitalWrite(FAN_1, LOW);
    digitalWrite(FAN_2, LOW);
    digitalWrite(peltier, LOW);
  }
  else
  {
    analogWrite(FAN_top, val);
    analogWrite(FAN_1, val);
    analogWrite(FAN_2, val);
    analogWrite(peltier, val);
  }
}
