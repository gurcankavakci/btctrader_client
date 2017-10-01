package com.ugurdonmez.trade;

import com.google.common.collect.ImmutableList;
import com.ugurdonmez.client.BTCTraderClient;
import com.ugurdonmez.client.BTCTraderClientFactory;
import com.ugurdonmez.data.*;
import com.ugurdonmez.setting.Constants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Optional;

import static com.ugurdonmez.trade.OrderType.BUY;
import static com.ugurdonmez.trade.OrderType.SELL;

public class ComissionedTradingAlgorithm implements Runnable {
    private final OrderType type;
    private final double end;
    private final BTCTraderClient btcTraderClient;
    private int clearCount = 0;
    private double exchangeSpread = 0;
    private double averagePrice = 0;
    private int spreadCount = 0;

    private OrderResult lastBuyOrder;
    private OrderResult lastSellOrder;

    private final static double taxRate = 0.18; // %18
    private final static double highestPriceForBuy = 20000;
    private final static double lowestPriceForSell = 5000;

    public ComissionedTradingAlgorithm(OrderType type, double end) {
        this.type = type;
        this.end = end;
        this.btcTraderClient = BTCTraderClientFactory.getInstance();
    }

    public static void main(String args[]) {
        ComissionedTradingAlgorithm buy = new ComissionedTradingAlgorithm(BUY, highestPriceForBuy);
        Thread buyThread = new Thread(buy);
        buyThread.start();
//
        ComissionedTradingAlgorithm sell = new ComissionedTradingAlgorithm(OrderType.SELL, lowestPriceForSell);
        Thread sellThread = new Thread(sell);
        sellThread.start();
    }

    @Override
    public void run() {
        clearData();

        while (true) {
            try {
                clearDataIfNeed();

                if (!checkBalance()) continue;

                if (!determineExchangeSpread()) continue;

                OrderBookResult orderBook = getOrderBookResult();
                if (orderBook == null) continue;

                if (!decideToContinue(orderBook)) continue;

                if (BUY.equals(this.type)) {
                    buy(orderBook);
                } else {
                    sell(orderBook);
                }

                Thread.sleep(1000);
            } catch (Exception e) {
                log("Uygulamada genel hata!", e);
            }
        }

    }

    private void sell(OrderBookResult orderBook) throws InterruptedException {
        double askStart = orderBook.getAsks()[0][0];
        double askSecondStart = orderBook.getAsks()[1][0];
        double askPrice = round(askStart - 0.01, 2);
        double askSecondPrice = round(askSecondStart - 0.01, 2);

        //Ýlk emir giriliyorsa
        if (lastSellOrder == null || lastSellOrder.getId() == null) {
            checkAndSell(askPrice);
        } else {
            //Eðer emir var ancak fiyat deðiþmiþ ise
            if (askStart != lastSellOrder.getPrice()) {

                cancelLastSellOrder();
                checkAndSell(askPrice);

                //Eðer emrimizin fiyatý gereksiz yüksek ise
            } else if (round(askSecondStart - lastSellOrder.getPrice(), 2) > 0.01) {

                cancelLastSellOrder();
                checkAndSell(askSecondPrice);

                //Ayný fiyattan emir vermek için para var ise
            } else {
                checkAndSell(askStart);
            }
        }

    }

    private void buy(OrderBookResult orderBook) throws InterruptedException {
        double bidStart = orderBook.getBids()[0][0];
        double bidSecondStart = orderBook.getBids()[1][0];
        double bidPrice = round(bidStart + 0.01, 2);
        double bidSecondPrice = round(bidSecondStart + 0.01, 2);

        //Ýlk emir giriliyorsa
        if (lastBuyOrder == null || lastBuyOrder.getId() == null) {
            checkAndBuy(bidPrice);
        } else {
            //Eðer emir var ancak fiyat deðiþmiþ ise
            if (bidStart != lastBuyOrder.getPrice()) {

                cancelLastBuyOrder();
                checkAndBuy(bidPrice);

                //Eðer emrimizin fiyatý gereksiz yüksek ise
            } else if (round(lastBuyOrder.getPrice() - bidSecondStart, 2) > 0.01) {

                cancelLastBuyOrder();
                checkAndBuy(bidSecondPrice);

                //Ayný fiyattan emir vermek için para var ise
            } else {
                checkAndBuy(bidStart);
            }
        }
    }

    private boolean checkAndBuy(double price) throws InterruptedException {
        if (!checkIfPriceInLimits(price)) return false;

        double currentAmount = calculateAmount(price);
        if (!checkAmountAvailable(currentAmount)) return false;

        Optional<OrderResult> orderResult = btcTraderClient.buyBTCTraderOrder(0, price, currentAmount, 0);
        orderResult.ifPresent(result -> this.lastBuyOrder = result);
        return true;
    }

    private boolean checkAndSell(double price) throws InterruptedException {
        if (!checkIfPriceInLimits(price)) return false;

        double currentAmount = calculateAmount(price);
        if (!checkAmountAvailable(currentAmount)) return false;

        Optional<OrderResult> orderResult = btcTraderClient.sellBTCTraderOrder(0, price, currentAmount, 0);
        orderResult.ifPresent(result -> this.lastSellOrder = result);
        return true;
    }

    private void cancelLastBuyOrder() throws InterruptedException {
        Optional<CancelOrderResult> cancelOrderResult = btcTraderClient.cancelBTCTraderOrder(lastBuyOrder.getId());
        int i = 0;
        while ((!cancelOrderResult.isPresent() || !cancelOrderResult.get().isResult()) && i < 5) {
            i++;
            log("Son islem silinemiyor!");
            Thread.sleep(1000);
            cancelOrderResult = btcTraderClient.cancelBTCTraderOrder(lastBuyOrder.getId());
        }
        this.lastBuyOrder = null;
    }

    private void cancelLastSellOrder() throws InterruptedException {
        Optional<CancelOrderResult> cancelOrderResult = btcTraderClient.cancelBTCTraderOrder(lastSellOrder.getId());
        int i = 0;
        while ((!cancelOrderResult.isPresent() || !cancelOrderResult.get().isResult()) && i < 5) {
            i++;
            log("Son islem silinemiyor!");
            Thread.sleep(1000);
            cancelOrderResult = btcTraderClient.cancelBTCTraderOrder(lastSellOrder.getId());
        }
        this.lastSellOrder = null;
    }

    private boolean checkAmountAvailable(double currentAmount) throws InterruptedException {
        if (BUY.equals(type) && currentAmount == 0) {
            log("BTC almak icin yeterli para yok: " + currentAmount + ", bekleniyor");
            Thread.sleep(1000);
            return false;
        } else if (SELL.equals(type) && currentAmount == 0) {
            log("BTC satmak icin yeterli btc yok, bekleniyor");
            Thread.sleep(1000);
            return false;
        }
        return true;
    }

    private boolean checkIfPriceInLimits(double bidPrice) throws InterruptedException {
        if (BUY.equals(this.type) && bidPrice > this.end) {
            log(this.type + " icin alis fiyati sinirin uzerinde: " + bidPrice);
            Thread.sleep(1000);
            return false;
        } else if (SELL.equals(this.type) && bidPrice < this.end) {
            log(this.type + " icin satis fiyati sinirin altinda: " + bidPrice);
            Thread.sleep(1000);
            return false;
        }
        return true;
    }

    private boolean decideToContinue(OrderBookResult orderBook) throws InterruptedException {
//        double spread;
//        try {
//            spread = round(orderBook.getAsks()[0][0] - orderBook.getBids()[0][0], 2);
//        } catch (Exception e) {
//            log("Fiyat farký hesaplanirken hata olustu.");
//            Thread.sleep(1000);
//            return false;
//        }
//
//        if (spread <= exchangeSpread) {
//            log(this.type + " için alýþ satýþ fiyat farký: " + spread + ", " + exchangeSpread + " den buyuk olmasi bekleniyor bekleniyor.");
//            deleteOrders();
//            Thread.sleep(1000);
//            return false;
//        }

        double spread;
        double ask = orderBook.getAsks()[0][0];
        double bid = orderBook.getBids()[0][0];
        try {
            if (SELL.equals(type)) {
                spread = round(ask - averagePrice, 2);
            } else {
                spread = round(averagePrice - bid, 2);
            }
        } catch (Exception e) {
            log("Fiyat farký hesaplanirken hata olustu.");
            Thread.sleep(1000);
            return false;
        }

        if (spread < exchangeSpread) {
            log(this.type + " için fiyat farki: " + spread + ", " + exchangeSpread + " den buyuk olmasi bekleniyor.");
            deleteOrders();
            
            if (spread < 0)
                Thread.sleep(60000);
            else
                Thread.sleep(5000);

            return false;
        }

        return true;
    }

    private void deleteOrders() {
        //açýk emirler siliniyor
        ImmutableList<OrderResult> openOrders = btcTraderClient.getBTCTraderOpenOrders();
        if (BUY.equals(this.type)) {
            openOrders.stream()
                    .filter(order -> order.getType().equals(Constants.BUY_BTC))
                    .forEach(order -> btcTraderClient.cancelBTCTraderOrder(order.getId()));
            lastBuyOrder = null;
        }
        if (SELL.equals(this.type)) {
            openOrders.stream()
                    .filter(order -> order.getType().equals(Constants.SELL_BTC))
                    .forEach(order -> btcTraderClient.cancelBTCTraderOrder(order.getId()));
            lastSellOrder = null;
        }
        log(type + " icin acik emirler temizlendi");

    }

    private OrderBookResult getOrderBookResult() throws InterruptedException {
        Optional<OrderBookResult> orderBookOptional = btcTraderClient.getBTCTraderOrderBook();

        if (!orderBookOptional.isPresent()) {
            log("Piyasa siparis verileri alinamadi. 10 sn bekleniyor...");
            Thread.sleep(10000);
            return null;
        }
        return orderBookOptional.get();
    }

    private boolean determineExchangeSpread() throws InterruptedException {
//        if (exchangeSpread == 0 || spreadCount > 60) {
//            Optional<TickerResult> btcTraderTicker = btcTraderClient.getBTCTraderTicker();
//            Optional<BalanceResult> btcTraderBalance = btcTraderClient.getBTCTraderBalance();
//
//            if (!btcTraderTicker.isPresent() || !btcTraderBalance.isPresent()) {
//                log("Piyasa verileri alinamadi. 10 sn bekleniyor...");
//                Thread.sleep(10000);
//                return false;
//            }
//            BalanceResult balanceResult = btcTraderBalance.get();
//            double makerFeePercentage = balanceResult.getMakerFeePercentage();
//
//            TickerResult tickerResult = btcTraderTicker.get();
//            double lastPrice = tickerResult.getLast();
//            double comission = round(lastPrice * makerFeePercentage, 2); //TODO balanceResult.getMakerFeePercentage();
//            double tax = round(comission * taxRate, 2);
//            exchangeSpread = round((comission + tax) * 2,2);//al sat olarak hesaplandýðý için 2 ile çarpýldý.
//
//            spreadCount = 0;
//        }
//        spreadCount++;


        if (averagePrice == 0 || exchangeSpread == 0 || spreadCount > 60) {
            Optional<TickerResult> btcTraderTicker = btcTraderClient.getBTCTraderTicker();
//            Optional<BalanceResult> btcTraderBalance = btcTraderClient.getBTCTraderBalance();

            if (!btcTraderTicker.isPresent()) {
                log("Piyasa verileri alinamadi. 10 sn bekleniyor...");
                Thread.sleep(10000);
                return false;
            }
//            BalanceResult balanceResult = btcTraderBalance.get();
//            double makerFeePercentage = balanceResult.getMakerFeePercentage();

            TickerResult tickerResult = btcTraderTicker.get();
//            double lastPrice = tickerResult.getLast();
//            double comission = round(lastPrice * makerFeePercentage, 2); //TODO balanceResult.getMakerFeePercentage();
//            double tax = round(comission * taxRate, 2);
//            exchangeSpread = round((comission + tax) * 2,2);//al sat olarak hesaplandýðý için 2 ile çarpýldý.
            averagePrice = tickerResult.getAverage();

            spreadCount = 0;
            exchangeSpread = 200; // todo spread asýl hesabý, elimdeki paradan ne kadar komisyon alacaklarýna göre hesaplanacak
        }
        spreadCount++;

        return true;
    }

    private void clearDataIfNeed() {
        if (clearCount > 60) {
            clearData();
            clearCount = 0;
        }
        clearCount++;
    }

    public void clearData() {
        //açýk emirler siliniyor
        deleteOrders();
    }

    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private double calculateAmount(double bidPrice) {
        Optional<BalanceResult> btcTraderBalance = btcTraderClient.getBTCTraderBalance();
        BalanceResult balanceResult;
        if (btcTraderBalance.isPresent()) {
            balanceResult = btcTraderBalance.get();
            if (BUY.equals(this.type)) {
                double fee = balanceResult.getMakerFeePercentage();
                double money = balanceResult.getMoneyAvailable();
                double moneyFee = round(money * fee, 2);
                double moneyTax = round(moneyFee * taxRate, 2);
                money = round(money - (moneyFee + moneyTax), 2);
                if (money < 1)
                    return 0;

                double amount = money / bidPrice;
                return round(amount, 8);
            } else if (SELL.equals(this.type)) {
                return balanceResult.getBitcoinAvailable();
            }
        } else {
            log("Hesap bakiyesi alinamadi.");
        }
        return 0;
    }

    private boolean checkBalance() throws InterruptedException {
        Optional<BalanceResult> btcTraderBalance = btcTraderClient.getBTCTraderBalance();
        BalanceResult balanceResult;
        if (btcTraderBalance.isPresent()) {
            balanceResult = btcTraderBalance.get();
            if (BUY.equals(this.type)) {
                double fee = balanceResult.getMakerFeePercentage();
                double money = balanceResult.getMoneyAvailable();
                double moneyFee = round(money * fee, 2);
                double moneyTax = round(moneyFee * taxRate, 2);
                money = round(money - (moneyFee + moneyTax), 2);
                if (money < 1) {
                    log(type + " icin TL yok 10 dk bekleniyor...");
                    Thread.sleep(600000);
                    return false;
                } else {
                    return true;
                }
            } else if (SELL.equals(this.type)) {
                if (balanceResult.getBitcoinAvailable() > 0) {
                    return true;
                } else {
                    log(type + " icin BTC yok 10 dk bekleniyor...");
                    Thread.sleep(600000);
                    return false;
                }
            }
        } else {
            log("Hesap bakiyesi alinamadi.");
            return false;
        }
        return false;
    }

    private void log(String msg, Exception e) {
        System.out.println(Calendar.getInstance().getTime() + " - " + msg);
        if (e != null)
            e.printStackTrace();
    }

    private void log(String msg) {
        log(msg, null);
    }
}
