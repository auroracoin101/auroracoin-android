package com.auroracoin.is;

/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;

import com.auroracoin.is.util.NetworkUtils;
import com.auroracoin.core.coins.CoinID;
import com.auroracoin.core.coins.CoinType;
import com.auroracoin.core.coins.FiatValue;
import com.auroracoin.core.coins.Value;
import com.auroracoin.core.util.ExchangeRateBase;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider {

    public static class ExchangeRate {
        @Nonnull
        public final ExchangeRateBase rate;
        public final String currencyCodeId;
        @Nullable
        public final String source;

        public ExchangeRate(@Nonnull final ExchangeRateBase rate,
                            final String currencyCodeId, @Nullable final String source) {
            this.rate = rate;
            this.currencyCodeId = currencyCodeId;
            this.source = source;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + '[' + rate.value1 + " ~ " + rate.value2 + ']';
        }
    }

    public static final String KEY_CURRENCY_ID = "currency_id";
    private static final String KEY_RATE_COIN = "rate_coin";
    private static final String KEY_RATE_FIAT = "rate_fiat";
    private static final String KEY_RATE_COIN_CODE = "rate_coin_code";
    private static final String KEY_RATE_FIAT_CODE = "rate_fiat_code";
    private static final String KEY_SOURCE = "source";

    private static final String QUERY_PARAM_OFFLINE = "offline";

    private ConnectivityManager connManager;
    private Configuration config;

    private Map<String, ExchangeRate> localToCryptoRates = null;
    private long localToCryptoLastUpdated = 0;
    private String lastLocalCurrency = null;

    private Map<String, ExchangeRate> cryptoToLocalRates = null;
    private long cryptoToLocalLastUpdated = 0;
    private String lastCryptoCurrency = null;

    private static final String BASE_URL = "https://ticker.coinomi.net/simple";
    private static final String TO_LOCAL_URL = BASE_URL + "/to-local/%s";
    private static final String TO_CRYPTO_URL = BASE_URL + "/to-crypto/%s";
    private static final String TO_CRYPTO_URL_NEW = "https://isx.is/api/stats?currency=%s&market=AUR";
    private static final String TO_LOCAL_URL_NEW = "https://isx.is/api/stats?currency=%s&market=AUR";
    private static final String COINOMI_SOURCE = "coinomi.com";

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));

        lastLocalCurrency = config.getCachedExchangeLocalCurrency();
        if (lastLocalCurrency != null) {
            localToCryptoRates = parseExchangeRates(
                    config.getCachedExchangeRatesJson(), lastLocalCurrency, true);
            localToCryptoLastUpdated = 0;
        }

        return true;
    }

    private static Uri.Builder contentUri(@Nonnull final String packageName, final boolean offline) {
        final Uri.Builder builder =
                Uri.parse("content://" + packageName + ".exchange_rates").buildUpon();
        if (offline)
            builder.appendQueryParameter(QUERY_PARAM_OFFLINE, "1");
        return builder;
    }

    public static Uri contentUriToLocal(@Nonnull final String packageName,
                                        @Nonnull final String coinSymbol,
                                        final boolean offline) {
        final Uri.Builder uri = contentUri(packageName, offline);
        uri.appendPath("to-local").appendPath(coinSymbol);
        return uri.build();
    }

    public static Uri contentUriToCrypto(@Nonnull final String packageName,
                                         @Nonnull final String localSymbol,
                                         final boolean offline) {
        final Uri.Builder uri = contentUri(packageName, offline);
        uri.appendPath("to-crypto").appendPath(localSymbol);
        return uri.build();
    }

    @Nullable
    public static ExchangeRate getRate(final Context context,
                                       @Nonnull final String coinSymbol,
                                       @Nonnull String localSymbol) {
        ExchangeRate rate = null;

        if (context != null) {
            final Uri uri = contentUriToCrypto(context.getPackageName(), localSymbol, true);
            final Cursor cursor = context.getContentResolver().query(uri, null,
                    ExchangeRatesProvider.KEY_CURRENCY_ID, new String[]{coinSymbol}, null);

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    rate = getExchangeRate(cursor);
                }
                cursor.close();
            }
        }

        return rate;
    }

    public static Map<String, ExchangeRate> getRates(final Context context,
                                                     @Nonnull String localSymbol) {
        ImmutableMap.Builder<String, ExchangeRate> builder = ImmutableMap.builder();

        if (context != null) {
            final Uri uri = contentUriToCrypto(context.getPackageName(), localSymbol, true);
            final Cursor cursor = context.getContentResolver().query(uri, null, null,
                    new String[]{null}, null);

            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                do {
                    ExchangeRate rate = getExchangeRate(cursor);
                    builder.put(rate.currencyCodeId, rate);
                } while (cursor.moveToNext());
                cursor.close();
            }
        }

        return builder.build();
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        final long now = System.currentTimeMillis();

        final List<String> pathSegments = uri.getPathSegments();
        if (pathSegments.size() != 2) {
            throw new IllegalArgumentException("Unrecognized URI: " + uri);
        }

        final boolean offline = uri.getQueryParameter(QUERY_PARAM_OFFLINE) != null;
        long lastUpdated;

        final String symbol;
        final boolean isLocalToCrypto;

        if (pathSegments.get(0).equals("to-crypto")) {
            isLocalToCrypto = true;
            symbol = pathSegments.get(1);
            lastUpdated = symbol.equals(lastLocalCurrency) ? localToCryptoLastUpdated : 0;
        } else if (pathSegments.get(0).equals("to-local")) {
            isLocalToCrypto = false;
            symbol = pathSegments.get(1);
            lastUpdated = symbol.equals(lastCryptoCurrency) ? cryptoToLocalLastUpdated : 0;
        } else {
            throw new IllegalArgumentException("Unrecognized URI path: " + uri);
        }

        if (!offline && (lastUpdated == 0 || now - lastUpdated > Constants.RATE_UPDATE_FREQ_MS)) {
            URL url;
            try {
                if (isLocalToCrypto) {
                    url = new URL(String.format(TO_CRYPTO_URL_NEW, symbol));
                } else {
                    url = new URL(String.format(TO_LOCAL_URL_NEW, symbol));
                }
            } catch (final MalformedURLException x) {
                throw new RuntimeException(x); // Should not happen
            }

            JSONObject newExchangeRatesJson = requestExchangeRatesJson(url);
            Map<String, ExchangeRate> newExchangeRates =
                    parseExchangeRates(newExchangeRatesJson, symbol, isLocalToCrypto);

            if (newExchangeRates != null) {
                if (isLocalToCrypto) {
                    localToCryptoRates = newExchangeRates;
                    localToCryptoLastUpdated = now;
                    lastLocalCurrency = symbol;
                    config.setCachedExchangeRates(lastLocalCurrency, newExchangeRatesJson);
                } else {
                    cryptoToLocalRates = newExchangeRates;
                    cryptoToLocalLastUpdated = now;
                    lastCryptoCurrency = symbol;
                }
            }
        }

        Map<String, ExchangeRate> exchangeRates = isLocalToCrypto ? localToCryptoRates : cryptoToLocalRates;

        if (exchangeRates == null)
            return null;

        final MatrixCursor cursor = new MatrixCursor(new String[]{BaseColumns._ID,
                KEY_CURRENCY_ID, KEY_RATE_COIN, KEY_RATE_COIN_CODE, KEY_RATE_FIAT, KEY_RATE_FIAT_CODE, KEY_SOURCE});

        if (selection == null) {
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate exchangeRate = entry.getValue();
                addRow(cursor, exchangeRate);
            }
        } else if (selection.equals(KEY_CURRENCY_ID)) {
            final ExchangeRate exchangeRate = exchangeRates.get(selectionArgs[0]);
            if (exchangeRate != null) {
                addRow(cursor, exchangeRate);
            }
        }

        return cursor;
    }

    private void addRow(MatrixCursor cursor, ExchangeRate exchangeRate) {
        final ExchangeRateBase rate = exchangeRate.rate;
        final String codeId = exchangeRate.currencyCodeId;
        cursor.newRow().add(codeId.hashCode()).add(codeId)
                .add(rate.value1.value).add(rate.value1.type.getSymbol())
                .add(rate.value2.value).add(rate.value2.type.getSymbol())
                .add(exchangeRate.source);
    }

    public static String getCurrencyCodeId(@Nonnull final Cursor cursor) {
        return cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_ID));
    }

    public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor) {
        final String codeId = getCurrencyCodeId(cursor);
        final CoinType type = CoinID.typeFromSymbol(cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN_CODE)));
        final Value rateCoin = Value.valueOf(type, cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN)));
        final String fiatCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT_CODE));
        final Value rateFiat = FiatValue.valueOf(fiatCode, cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT)));
        final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

        ExchangeRateBase rate = new ExchangeRateBase(rateCoin, rateFiat);
        return new ExchangeRate(rate, codeId, source);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(final Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Nullable
    private JSONObject requestExchangeRatesJson(final URL url) {
        // Return null if no connection
        final NetworkInfo activeInfo = connManager.getActiveNetworkInfo();
        if (activeInfo == null || !activeInfo.isConnected()) return null;

        final long start = System.currentTimeMillis();

        OkHttpClient client = NetworkUtils.getHttpClient(getContext().getApplicationContext());
        Request request = new Request.Builder().url(url).build();

        try {
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                log.info("fetched exchange rates from {}, took {} ms", url,
                        System.currentTimeMillis() - start);
                return new JSONObject(response.body().string());
            } else {
                log.warn("Error HTTP code '{}' when fetching exchange rates from {}",
                        response.code(), url);
            }
        } catch (IOException e) {
            log.warn("Error '{}' when fetching exchange rates from {}", e.getMessage(), url);
        } catch (JSONException e) {
            log.warn("Could not parse exchange rates JSON: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, ExchangeRate> parseExchangeRates(JSONObject json, String fromSymbol, boolean isLocalToCrypto) {
        if (json == null) return null;

        final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        try {
            final String toSymbol = "AUR";
            Gson gson = new Gson();
            Rate rateObject = gson.fromJson(String.valueOf(json), Rate.class);

            // List<Rate.PriceData> data = rateObject.getStatsPrices().getData();
            // final Double rateStr = data.get(data.size()-1).getPrice();
            final Double rateStr = rateObject.getStatsPrices().getBid();
            if (rateStr != null) {
                try {
                    CoinType type = CoinID.typeFromSymbol(toSymbol);
                    String localSymbol = fromSymbol;
                    final Value rateCoin = type.oneCoin();
                    final Value rateLocal = FiatValue.parse(localSymbol, String.valueOf(rateStr));

                    ExchangeRateBase rate = new ExchangeRateBase(rateCoin, rateLocal);

                    final String rateSymbol = isLocalToCrypto ? "AUR" : fromSymbol;
                    rates.put(rateSymbol, new ExchangeRate(rate, rateSymbol, COINOMI_SOURCE));
                } catch (final Exception x) {
                    log.debug("ignoring {}/{}: {}", toSymbol, fromSymbol, x.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("problem parsing exchange rates: {}", e.getMessage());
        }

        if (rates.size() == 0) {
            return null;
        } else {
            return rates;
        }
    }

    class Rate {

        @SerializedName("stats")
        public RateData statsPrices;

        public RateData getStatsPrices() {
            return statsPrices;
        }

        public void setStatsPrices(RateData statsPrices) {
            this.statsPrices = statsPrices;
        }

        class RateData {
            public String market;
            public Double bid;
            public Double ask;
            public Double last_price;
            public String last_transaction_type;
            public String last_transaction_currency;
            public Double daily_change;
            public Double daily_change_percent;
            @SerializedName("max") public Double daily_max;
            @SerializedName("min") public Double daily_min;
            @SerializedName("open") public Double daily_open;
            public Double market_cap;
            public Double global_units;
            public Double global_volume;
            @SerializedName("24h_volume") public Double volume_24h;
            @SerializedName("24h_volume_buy") public Double volume_buy_24h;
            @SerializedName("24h_volume_sell") public Double volume_sell_24h;
            @SerializedName("1h_volume") public Double volume_1h;
            @SerializedName("1h_volume_buy") public Double volume_buy_1h;
            @SerializedName("1h_volume_sell")  public Double volume_sell_1h;
            public String currency;
            // public List<PriceData> data = new ArrayList<PriceData>();



            public Double getBid() {
                return bid;
            }

            public void setBid(Double bid) {
                this.bid = bid;
            }

            public Double getAsk() {
                return ask;
            }

            public void setAsk(Double ask) {
                this.ask = ask;
            }

            public Double getLast_price() {
                return last_price;
            }

            public void setLast_price(Double last_price) {
                this.last_price = last_price;
            }

            public String getLast_transaction_type() {
                return last_transaction_type;
            }

            public void setLast_transaction_type(String last_transaction_type) {
                this.last_transaction_type = last_transaction_type;
            }

            public String getLast_transaction_currency() {
                return last_transaction_currency;
            }

            public void setLast_transaction_currency(String last_transaction_currency) {
                this.last_transaction_currency = last_transaction_currency;
            }

            public Double getDaily_change() {
                return daily_change;
            }

            public void setDaily_change(Double daily_change) {
                this.daily_change = daily_change;
            }

            public Double getDaily_change_percent() {
                return daily_change_percent;
            }

            public void setDaily_change_percent(Double daily_change_percent) {
                this.daily_change_percent = daily_change_percent;
            }

            public Double getDaily_max() {
                return daily_max;
            }

            public void setDaily_max(Double daily_max) {
                this.daily_max = daily_max;
            }

            public Double getDaily_min() {
                return daily_min;
            }

            public void setDaily_min(Double daily_min) {
                this.daily_min = daily_min;
            }

            public Double getDaily_open() {
                return daily_open;
            }

            public void setDaily_open(Double daily_open) {
                this.daily_open = daily_open;
            }

            public Double getVolume_24h() {
                return volume_24h;
            }

            public void setVolume_24h(Double volume_24h) {
                this.volume_24h = volume_24h;
            }

            public Double getVolume_buy_24h() {
                return volume_buy_24h;
            }

            public void setVolume_buy_24h(Double volume_buy_24h) {
                this.volume_buy_24h = volume_buy_24h;
            }

            public Double getVolume_sell_24h() {
                return volume_sell_24h;
            }

            public void setVolume_sell_24h(Double volume_sell_24h) {
                this.volume_sell_24h = volume_sell_24h;
            }

            public Double getVolume_1h() {
                return volume_1h;
            }

            public void setVolume_1h(Double volume_1h) {
                this.volume_1h = volume_1h;
            }

            public Double getVolume_buy_1h() {
                return volume_buy_1h;
            }

            public void setVolume_buy_1h(Double volume_buy_1h) {
                this.volume_buy_1h = volume_buy_1h;
            }

            public Double getVolume_sell_1h() {
                return volume_sell_1h;
            }

            public void setVolume_sell_1h(Double volume_sell_1h) {
                this.volume_sell_1h = volume_sell_1h;
            }

            public Double getMarket_cap() {
                return market_cap;
            }

            public void setMarket_cap(Double market_cap) {
                this.market_cap = market_cap;
            }

            public Double getGlobal_units() {
                return global_units;
            }

            public void setGlobal_units(Double global_units) {
                this.global_units = global_units;
            }

            public Double getGlobal_volume() {
                return global_volume;
            }

            public void setGlobal_volume(Double global_volume) {
                this.global_volume = global_volume;
            }


            public String getMarket() {
                return market;
            }

            public void setMarket(String market) {
                this.market = market;
            }

            public String getCurrency() {
                return currency;
            }

            public void setCurrency(String currency) {
                this.currency = currency;
            }

            //public List<PriceData> getData() { return data; }

            // public void setData(List<PriceData> data) { this.data = data; }
        }

        /* class PriceData {
            public String date;
            public Double price;

            public String getDate() {
                return date;
            }

            public void setDate(String date) {
                this.date = date;
            }

            public Double getPrice() {
                return price;
            }

            public void setPrice(Double price) {
                this.price = price;
            }
        }
        */

    }

    class RateHistorical {

        @SerializedName("historical-prices")
        public RateData historicalPrices;

        public RateData getHistoricalPrices() {
            return historicalPrices;
        }

        public void setHistoricalPrices(RateData historicalPrices) {
            this.historicalPrices = historicalPrices;
        }

        class RateData {
            public String market;
            public String currency;
            public List<PriceData> data = new ArrayList<PriceData>();

            public String getMarket() {
                return market;
            }

            public void setMarket(String market) {
                this.market = market;
            }

            public String getCurrency() {
                return currency;
            }

            public void setCurrency(String currency) {
                this.currency = currency;
            }

            public List<PriceData> getData() {
                return data;
            }

            public void setData(List<PriceData> data) {
                this.data = data;
            }
        }

        class PriceData {
            public String date;
            public Double price;

            public String getDate() {
                return date;
            }

            public void setDate(String date) {
                this.date = date;
            }

            public Double getPrice() {
                return price;
            }

            public void setPrice(Double price) {
                this.price = price;
            }
        }

    }

}
