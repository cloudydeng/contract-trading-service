# contract-trading-service

Spring Boot skeleton for a contract trading system without gateway.

Supported order rules:
- `orderType`: `LIMIT`, `MARKET`
- `timeInForce`: `GTC`, `IOC`, `FOK`

## Run

```bash
mvn spring-boot:run
```

## APIs

- `GET /api/health`
- `POST /api/v1/orders`
- `GET /api/v1/orders/{orderId}`
- `DELETE /api/v1/orders/{orderId}`
- `POST /api/v1/positions/{symbol}/close-market`
- `GET /api/v1/trades?userId=10001`
- `GET /api/v1/trades?orderId=123`

Example order request:

```json
{
  "userId": 10001,
  "symbol": "BTCUSDT",
  "side": "BUY",
  "orderType": "LIMIT",
  "timeInForce": "GTC",
  "quantity": 0.01,
  "price": 65000,
  "reduceOnly": false
}
```

Example close-market request:

```json
{
  "userId": 10001,
  "quantity": 0.005,
  "markPrice": 65200
}
```
# contract-trading-service
