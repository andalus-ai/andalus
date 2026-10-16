---
name: time-series-forecasting
description: Perform statistical and deep learning time series forecasting (ARIMA, Holt-Winters, LSTM, Chronos) using Aqli's embedded ML engine.
metadata:
  short-description: Time series analysis and forecasting
  category: machine-learning
  difficulty: intermediate
---

# Time Series Forecasting Skill

Perform univariate and multivariate time series forecasting, trend decomposition, anomaly detection, and predictive modeling using Aqli's `tafkir-ml-timeseries` engine.

## When to Use

- Predicting future values from sequential historical metrics (e.g. server CPU/memory utilization, revenue, traffic, financial data).
- Fitting statistical models (ARIMA, Auto-ARIMA, Exponential Smoothing / Holt-Winters).
- Training neural sequence-to-sequence models (Stacked LSTM).
- Running zero-shot forecasting with foundation models (Amazon Chronos).
- Calculating forecasting accuracy metrics (MAE, RMSE, MAPE, sMAPE, MASE, R2).

## Core Forecasting Models in Aqli

| Model Type | Algorithm | Best Suited For | Typical Horizon |
|---|---|---|---|
| **ARIMA** | `ArimaForecaster(p, d, q)` | Non-seasonal or differenced series with auto-correlation | 1–24 steps |
| **Exponential Smoothing** | `ExponentialSmoothing.holtWinters(period, additive)` | Multi-seasonal data with strong trend and seasonality | 1–52 steps |
| **Deep LSTM** | `LstmForecaster(lookback, hiddenDim, layers)` | Complex non-linear sequences and long-range patterns | Multi-step seq2seq |
| **Foundation Models** | Chronos (T5-based zero-shot forecaster) | General zero-shot forecasting without local calibration | Arbitrary horizon |

## Java SDK Usage in Andalus

```java
import tech.kayys.tafkir.ml.timeseries.api.*;
import tech.kayys.tafkir.ml.timeseries.models.*;
import tech.kayys.tafkir.ml.timeseries.metrics.ForecastMetrics;

// 1. Prepare historical observations
double[] historicalData = new double[]{ 112.0, 118.0, 132.0, 129.0, 121.0, 135.0 };

// 2. Select and fit a forecaster (e.g., Holt-Winters with 12-month seasonality)
Forecaster model = ExponentialSmoothing.holtWinters(12, true);
model.fit(historicalData);

// 3. Generate 12 future steps
double[] predictions = model.predict(12);

// 4. Alternatively, use one-shot ForecastRequest API:
ForecastResult result = model.forecast(ForecastRequest.of(historicalData, 12));
System.out.println("Predicted values: " + Arrays.toString(result.values()));

// 5. Evaluate accuracy metrics against actual validation data
ForecastMetrics.EvaluationReport report = ForecastMetrics.evaluate(actualValues, predictions);
System.out.printf("MAE: %.2f | RMSE: %.2f | MAPE: %.2f%%\n", 
    report.mae(), report.rmse(), report.mape());
```

## CLI Listing and Model Discovery

You can list time-series models available in the local registry:

```bash
andalus aqli list -t timeseries
# or directly via Aqli
aqli list -t timeseries
```

## Supported Evaluation Metrics

- **MAE** (Mean Absolute Error): Average magnitude of errors.
- **RMSE** (Root Mean Squared Error): Penalizes large outlier deviations.
- **MAPE** (Mean Absolute Percentage Error): Scale-independent percentage accuracy.
- **sMAPE** (Symmetric MAPE): Symmetric percentage error bounded between 0% and 200%.
- **MASE** (Mean Absolute Scaled Error): Compares accuracy relative to naive persistence baseline.
- **R2** (Coefficient of Determination): Proportion of variance explained by model.
