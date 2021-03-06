/**
 * Copyright (C) 2012 - 2014 Xeiam LLC http://xeiam.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.xeiam.xchange.coinfloor.streaming;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xeiam.xchange.ExchangeException;
import com.xeiam.xchange.coinfloor.dto.streaming.CoinfloorExchangeEvent;
import com.xeiam.xchange.coinfloor.dto.streaming.CoinfloorStreamingConfiguration;
import com.xeiam.xchange.currency.CurrencyPair;
import com.xeiam.xchange.dto.Order;
import com.xeiam.xchange.dto.account.AccountInfo;
import com.xeiam.xchange.dto.marketdata.OrderBook;
import com.xeiam.xchange.dto.marketdata.Ticker;
import com.xeiam.xchange.dto.marketdata.Trades;
import com.xeiam.xchange.dto.trade.MarketOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xeiam.xchange.ExchangeSpecification;
import com.xeiam.xchange.service.streaming.BaseWebSocketExchangeService;
import com.xeiam.xchange.service.streaming.ExchangeEvent;
import com.xeiam.xchange.service.streaming.ExchangeEventType;
import com.xeiam.xchange.service.streaming.StreamingExchangeService;

/**
 * @author obsessiveOrange
 */
public class CoinfloorStreamingExchangeService extends BaseWebSocketExchangeService implements StreamingExchangeService {

  private final Logger logger = LoggerFactory.getLogger(CoinfloorStreamingExchangeService.class);

  private final CoinfloorStreamingConfiguration configuration;
  private final CoinfloorEventListener exchangeEventListener;
  private final BlockingQueue<ExchangeEvent> systemEventQueue = new LinkedBlockingQueue<ExchangeEvent>(1024);
  private final BlockingQueue<CoinfloorExchangeEvent> updateEventQueue = new LinkedBlockingQueue<CoinfloorExchangeEvent>(1024);
  
  ObjectMapper jsonObjectMapper;

  /**
   * @param exchangeSpecification
   * @param exchangeStreamingConfiguration
   */
  public CoinfloorStreamingExchangeService(ExchangeSpecification exchangeSpecification, CoinfloorStreamingConfiguration exchangeStreamingConfiguration) {

    super(exchangeSpecification, exchangeStreamingConfiguration);

    this.configuration = exchangeStreamingConfiguration;
    this.exchangeEventListener = new CoinfloorEventListener(consumerEventQueue, systemEventQueue);

    this.jsonObjectMapper = new ObjectMapper();

  }

  @Override
  public void connect() {

    String apiBase;
    if (configuration.isEncryptedChannel()) {
      apiBase = String.format("%s:%s", exchangeSpecification.getSslUriStreaming(), exchangeSpecification.getPort());
    }
    else {
      apiBase = String.format("%s:%s", exchangeSpecification.getPlainTextUriStreaming(), exchangeSpecification.getPort());
    }

    URI uri = URI.create(apiBase);

    Map<String, String> headers = new HashMap<String, String>(1);
    headers.put("Origin", String.format("%s:%s", exchangeSpecification.getHost(), exchangeSpecification.getPort()));

    logger.debug("Streaming URI='{}'", uri);

    // Use the default internal connect
    internalConnect(uri, exchangeEventListener, headers);
    
    if(configuration.getauthenticateOnConnect()){authenticate();}
  }

  public void authenticate(){
	if(exchangeSpecification.getUserName() == null || exchangeSpecification.getUserName() == null || exchangeSpecification.getUserName() == null){
		throw new ExchangeException("Username (UserID), Cookie, and Password cannot be null");
	}
	try{Long.valueOf(exchangeSpecification.getUserName());
	}catch(NumberFormatException e){throw new ExchangeException("Username (UserID) must be the string representation of a integer or long value.");}
	  
	CoinfloorExchangeEvent event;
	try {
		for(event = getNextEvent(); !event.getEventType().equals(ExchangeEventType.WELCOME); event = getNextEvent()){}
	} catch (InterruptedException e) {throw new ExchangeException("Interrupted while attempting to authenticate");}
	    	  
	RequestFactory.CoinfloorAuthenticationRequest authVars = new RequestFactory.CoinfloorAuthenticationRequest(
	    Long.valueOf(exchangeSpecification.getUserName()), 
	    (String)exchangeSpecification.getExchangeSpecificParametersItem("cookie"), 
	    exchangeSpecification.getPassword(), 
	    (String) event.getPayloadItem("nonce"));
	
    try {
      send(jsonObjectMapper.writeValueAsString(authVars));
    } catch (JsonProcessingException e) {
      throw new ExchangeException("Cannot convert Object to String", e);
    }

  }
	  
  private CoinfloorExchangeEvent doNewRequest(final Object requestObject, ExchangeEventType expectedEventType){
    try{
    	try{
		  logger.debug("Sent message: " + jsonObjectMapper.writeValueAsString(requestObject));
	      send(jsonObjectMapper.writeValueAsString(requestObject));
	    }catch (JsonProcessingException e) {throw new ExchangeException("Cannot convert Object to String", e);}
		
		ExchangeEventType nextEventType = checkNextSystemEvent().getEventType();
		while(!nextEventType.equals(expectedEventType)){
			if(nextEventType.equals(ExchangeEventType.USER_WALLET_UPDATE) || nextEventType.equals(ExchangeEventType.ORDER_ADDED) || 
					nextEventType.equals(ExchangeEventType.TRADE) || nextEventType.equals(ExchangeEventType.ORDER_CANCELED) || 
					nextEventType.equals(ExchangeEventType.TICKER) || nextEventType.equals(ExchangeEventType.WELCOME) || 
					nextEventType.equals(ExchangeEventType.AUTHENTICATION)){
				updateEventQueue.put(
						(CoinfloorExchangeEvent)getNextSystemEvent());
			}
			nextEventType = checkNextSystemEvent().getEventType();
		}
		return getNextSystemEvent();
    }catch(Exception e){throw new ExchangeException("Error processing request", e);}
  }
  
  /**
   * Get user's balances
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorBalances (key "raw")
   * > A generic object of type AccountInfo (key "generic")
   */
  public CoinfloorExchangeEvent getBalances() {
	return doNewRequest(new RequestFactory.GetBalancesRequest(), ExchangeEventType.USER_WALLET);
  }

  /**
   * Get user's open orders
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorOpenOrders (key "raw")
   * > A generic object of type OpenOrders (key "generic")
   */
  public CoinfloorExchangeEvent getOrders() {
	  return doNewRequest(new RequestFactory.GetOrdersRequest(), ExchangeEventType.USER_ORDERS_LIST);
  }

  /**
   * Place an order
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorPlaceOrder (key "raw")
   * > A generic object of type String, representing the orderID (key "generic")
   */
  public CoinfloorExchangeEvent placeOrder(Order order) {
	  return doNewRequest(new RequestFactory.PlaceOrderRequest(order), ExchangeEventType.USER_ORDER);
  }

  /**
   * Cancel an order
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorCancelOrder (key "raw")
   * > A generic object of type LimitOrder, representing the cancelled order (key "generic")
   */
  public CoinfloorExchangeEvent cancelOrder(int orderID) {
	  return doNewRequest(new RequestFactory.CancelOrderRequest(orderID), ExchangeEventType.USER_ORDER_CANCELED);
  }

  /**
   * Get past 30-day trade volume
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorTradeVolume (key "raw")
   * > A generic object of type BigDecimal, representing the past-30 day volume (key "generic")
   */
  public CoinfloorExchangeEvent getTradeVolume(String currency) {
	  return doNewRequest(new RequestFactory.GetTradeVolumeRequest(currency), ExchangeEventType.USER_TRADE_VOLUME);
  }

  /**
   * Estimate the results of a market order
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorEstimateMarketOrder (key "raw")
   * 
   * Note that this method has no (useful) generic return. The "generic" key corresponds to the same item as the "raw" key
   */
  public CoinfloorExchangeEvent estimateMarketOrder(MarketOrder order) {
	  return doNewRequest(new RequestFactory.EstimateMarketOrderRequest(order), ExchangeEventType.USER_MARKET_ORDER_EST);
  }

  /**
   * Watch the orderbook
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorOrderbookReturn (key "raw")
   * > A generic object of type Depth (key "generic")
   */
  public CoinfloorExchangeEvent watchOrders(String tradableIdentifier, String tradingCurrency) {
	  return doNewRequest(new RequestFactory.WatchOrdersRequest(tradableIdentifier, tradingCurrency), ExchangeEventType.SUBSCRIBE_ORDERS);
  }

  /**
   * Stop watching the orderbook
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorOrderbookReturn (key "raw")
   * > A generic object of type Depth (key "generic")
   */
  public CoinfloorExchangeEvent unwatchOrders(String tradableIdentifier, String tradingCurrency) {
	  return doNewRequest(new RequestFactory.UnwatchOrdersRequest(tradableIdentifier, tradingCurrency), ExchangeEventType.SUBSCRIBE_ORDERS);
  }

  /**
   * Watch the ticker feed
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorTicker (key "raw")
   * > A generic object of type Ticker (key "generic")
   */
  public CoinfloorExchangeEvent watchTicker(String tradableIdentifier, String tradingCurrency) {
	  return doNewRequest(new RequestFactory.WatchTickerRequest(tradableIdentifier, tradingCurrency), ExchangeEventType.SUBSCRIBE_TICKER);
  }

  /**
   * Stop watching the ticker feed
   * 
   * Upon receipt of response, a CoinfloorExchangeEvent with payload Map<String, Object>, consisting of:
   * > A raw object of type CoinfloorTicker (key "raw")
   * > A generic object of type Ticker (key "generic")
   */
  public CoinfloorExchangeEvent unwatchTicker(String tradableIdentifier, String tradingCurrency) {
	  return doNewRequest(new RequestFactory.UnwatchTickerRequest(tradableIdentifier, tradingCurrency), ExchangeEventType.SUBSCRIBE_TICKER);
  }
  
  /**
   * Retrieves cached AccountInfo.
   * WARNING: EXPERIMENTAL METHOD
   * 
   * @return the AccountInfo, as updated by last BalancesChanged event
   * @throws ExchangeException if getBalances() method has not been called, or data not recieved yet.
   */
  public AccountInfo getCachedAccountInfo() {
	  return exchangeEventListener.getAdapterInstance().getCachedAccountInfo();
  }
  
  /**
   * Retrieves cached OrderBook.
   * WARNING: EXPERIMENTAL METHOD
   * 
   * @return the OrderBook, as updated by last OrderOpened, OrdersMatched or OrderClosed event
   * @throws ExchangeException if watchOrders() method has not been called, or data not recieved yet.
   */
  public OrderBook getCachedOrderBook() {
	  return exchangeEventListener.getAdapterInstance().getCachedOrderBook();
  }
  
  /**
   * Retrieves cached Trades.
   * WARNING: EXPERIMENTAL METHOD
   * 
   * @return the Trades, as updated by last OrdersMatched event
   * @throws ExchangeException if watchOrders() method has not been called, or no trades have occurred yet.
   */
  public Trades getCachedTrades() {
	  return exchangeEventListener.getAdapterInstance().getCachedTrades();
  }
  
  /**
   * Retrieves cached Ticker.
   * WARNING: EXPERIMENTAL METHOD
   * 
   * @return the Ticker, as updated by last TickerChanged event
   * @throws ExchangeException if watchTicker() method has not been called, or no ticker data has been recieved.
   */
  public Ticker getCachedTicker() {
	  return exchangeEventListener.getAdapterInstance().getCachedTicker();
  }
  
  @Override
  public List<CurrencyPair> getExchangeSymbols() {

    return null;
  }

  @Override
  public CoinfloorExchangeEvent getNextEvent() throws InterruptedException {
    return (CoinfloorExchangeEvent) super.getNextEvent();
  } 
  
  public CoinfloorExchangeEvent getNextSystemEvent() throws InterruptedException {

	  CoinfloorExchangeEvent event = (CoinfloorExchangeEvent)systemEventQueue.take();
    return event;
  }

  public synchronized CoinfloorExchangeEvent checkNextSystemEvent() throws InterruptedException {
	
	while(systemEventQueue.isEmpty()){TimeUnit.MILLISECONDS.sleep(100);}
	CoinfloorExchangeEvent event = (CoinfloorExchangeEvent)systemEventQueue.peek();
    return event;
  }
}
