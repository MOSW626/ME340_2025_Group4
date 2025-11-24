#define FAN_top 3
#define FAN_1 4
#define FAN_2 5
#define pel1 6
#define pel2 7
#define pel3 8
#define pel4 9

void setup()
{
  pinMode(FAN_top, OUTPUT);
  pinMode(FAN_1, OUTPUT);
  pinMode(FAN_2, OUTPUT);
  pinMode(pel1, OUTPUT);
  pinMode(pel2, OUTPUT);
  pinMode(pel3, OUTPUT);
  pinMode(pel4, OUTPUT);

  digitalWrite(FAN_top, LOW);
  digitalWrite(FAN_1, LOW);
  digitalWrite(FAN_2, LOW);
  digitalWrite(pel1, HIGH);
  digitalWrite(pel2, HIGH);
  digitalWrite(pel3, HIGH);
  digitalWrite(pel4, HIGH);

  Serial.begin(9600);
}

void loop()
{
  int val = analogRead(A1)/4;
  analogWrite(FAN_top, val);
  analogWrite(FAN_1, val);
  analogWrite(FAN_2, val);
  if(val < 20)
  {
    digitalWrite(pel1, HIGH);
    digitalWrite(pel2, HIGH);
    digitalWrite(pel3, HIGH);
    digitalWrite(pel4, HIGH);
  }
  else
  {
    analogWrite(pel1, LOW);
    analogWrite(pel2, LOW);
    analogWrite(pel3, LOW);
    analogWrite(pel4, LOW);
  }
}
