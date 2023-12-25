/*
 * Copyright (c) 2012-2013 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.samplebroker;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.tools.picocli.CommandLine.Help;
import org.joda.time.Instant;
import org.powertac.common.Broker;
import org.powertac.common.Competition;
import org.powertac.common.CustomerInfo;
import org.powertac.common.Rate;
import org.powertac.common.TariffSpecification;
import org.powertac.common.TariffTransaction;
import org.powertac.common.TimeService;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.msg.CustomerBootstrapData;
import org.powertac.common.msg.SimEnd;
import org.powertac.common.msg.TariffRevoke;
import org.powertac.common.msg.TariffStatus;
import org.powertac.common.repo.CustomerRepo;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.samplebroker.core.BrokerPropertiesService;
import org.powertac.samplebroker.information.CustomerSubscriptionInformation;
import org.powertac.samplebroker.information.CustomerUsageInformation;
import org.powertac.samplebroker.information.TariffMarketInformation;
import org.powertac.samplebroker.information.UsageRecord;
import org.powertac.samplebroker.interfaces.Activatable;
import org.powertac.samplebroker.interfaces.BrokerContext;
import org.powertac.samplebroker.interfaces.Initializable;
import org.powertac.samplebroker.interfaces.MarketManager;
import org.powertac.samplebroker.interfaces.MessageManager;
import org.powertac.samplebroker.interfaces.PortfolioManager;
import org.powertac.samplebroker.messages.BalancingMarketInformation;
import org.powertac.samplebroker.messages.CapacityTransactionInformation;
import org.powertac.samplebroker.messages.CashPositionInformation;
import org.powertac.samplebroker.messages.DistributionInformation;
import org.powertac.samplebroker.messages.GameInformation;
import org.powertac.samplebroker.messages.MarketTransactionInformation;
import org.powertac.samplebroker.util.Helper;
import org.powertac.samplebroker.util.JSON_API;
import org.powertac.samplebroker.util.MABBasedDR;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;

@Service // Spring creates a single instance at startup
public class PortfolioManagerService
implements PortfolioManager, Initializable, Activatable
{
  static Logger log = LogManager.getLogger(PortfolioManagerService.class);

  private BrokerContext brokerContext; // master

  // Spring fills in Autowired dependencies through a naming convention
  @Autowired
  private BrokerPropertiesService propertiesService;

  @Autowired
  private TimeslotRepo timeslotRepo;

  @Autowired
  private TariffRepo tariffRepo;

  @Autowired
  private CustomerRepo customerRepo;

  @Autowired
  private MarketManager marketManager;

  @Autowired
  private MessageManager messageManager;

  @Autowired
  private TimeService timeService;

  // ---- Portfolio records -----
  // Customer records indexed by power type and by tariff. Note that the
  // CustomerRecord instances are NOT shared between these structures, because
  // we need to keep track of subscriptions by tariff.
  private Map<PowerType, Map<CustomerInfo, CustomerRecord>> customerProfiles;
  private Map<TariffSpecification, Map<CustomerInfo, CustomerRecord>> customerSubscriptions;
  private Map<PowerType, List<TariffSpecification>> competingTariffs;
  private Map<PowerType, List<TariffSpecification>> ownTariffs;

  // Keep track of a benchmark price to allow for comparisons between
  // tariff evaluations
  private double benchmarkPrice = 0.0;

  // These customer records need to be notified on activation
  private List<CustomerRecord> notifyOnActivation = new ArrayList<>();

  // Configurable parameters for tariff composition
  // Override defaults in src/main/resources/config/broker.config
  // or in top-level config file
  @ConfigurableValue(valueType = "Double", description = "target profit margin")
  private double defaultMargin = 0.5;

  @ConfigurableValue(valueType = "Double", description = "Fixed cost/kWh")
  private double fixedPerKwh = -0.06;

  @ConfigurableValue(valueType = "Double", description = "Default daily meter charge")
  private double defaultPeriodicPayment = -1.0;

  @ConfigurableValue(valueType = "Double", description = "Fixed cost/kWh for distribution")
  private double distributionCharge = -0.02;

  CustomerUsageInformation custUsageInfo = null;

  int[] blocks = {5, 9, 10, 16, 17, 22, 23, 5};
  // double[] discounts = {0.015, 0.030, 0.045, 0.060, 0.075, 0.090, 0.105, 0.120, 0.135, 0.150};
  double discountMultiplier = 0.015;         // offline algo works for integer discount 1 to 10, which needs to be multiplied with actual discount miltiplier

  int OFFSET = 24;
  double discount;

  double dfrate=-0.5;
  double dfrateProd=-0.5;

  private BalancingMarketInformation balancingMarketInformation;
  private CashPositionInformation cashPositionInformation;
  private GameInformation gameInformation;
  private MarketTransactionInformation marketTransactionInformation;
  private TariffMarketInformation tariffMarketInformation;
  private DistributionInformation distributionInformation;
  private CapacityTransactionInformation capacityTransactionInformation;
  private MABBasedDR mabBasedDR;

  //MongoDB client and Database
  MongoClient mongoClient;

  //MongoDB database
  DB mongoDatabase;

  String dbname; 

  FileWriter accountingInformation;

  Random rand;

  /**
   * Default constructor.
   */
  public PortfolioManagerService ()
  {
    super();
  }

  /**
   * Per-game initialization. Registration of message handlers is automated.
   */
  @Override // from Initializable
  public void initialize (BrokerContext context)
  {
    this.brokerContext = context;
    propertiesService.configureMe(this);
    customerProfiles = new HashMap<>();
    customerSubscriptions = new HashMap<>();
    competingTariffs = new HashMap<>();
    ownTariffs = new HashMap<>();
    mabBasedDR = new MABBasedDR(10, 4);

    custUsageInfo = messageManager.getCustomerUsageInformation();
    dfrate=0.0;
    dfrateProd = 0.0;

    tariffMarketInformation = new TariffMarketInformation();

    rand = new Random();

    dbname = "Test";
    try
    {
        mongoClient = new MongoClient("localhost", 27017);
        mongoDatabase = mongoClient.getDB(dbname);
    }
    catch(Exception e)
    {
        log.warn("Mongo DB connection Exception " + e.toString());
    }
    log.info(" Connected to Database " + dbname + " -- Broker Initialize");
    System.out.println("Connected to Database " + dbname + " from Initialize in PortfolioManagerService");

    notifyOnActivation.clear();
  }

  // -------------- data access ------------------

  /**
   * Returns the CustomerRecord for the given type and customer, creating it
   * if necessary.
   */
  CustomerRecord getCustomerRecordByPowerType (PowerType type, CustomerInfo customer)
  {
    Map<CustomerInfo, CustomerRecord> customerMap = customerProfiles.get(type);
    if (customerMap == null)
    {
      customerMap = new HashMap<>();
      customerProfiles.put(type, customerMap);
    }
    CustomerRecord record = customerMap.get(customer);
    if (record == null)
    {
      record = new CustomerRecord(customer);
      customerMap.put(customer, record);
    }
    return record;
  }

  /**
   * Returns the customer record for the given tariff spec and customer,
   * creating it if necessary.
   */
  CustomerRecord getCustomerRecordByTariff (TariffSpecification spec, CustomerInfo customer)
  {
    Map<CustomerInfo, CustomerRecord> customerMap = customerSubscriptions.get(spec);
    if (customerMap == null)
    {
      customerMap = new HashMap<>();
      customerSubscriptions.put(spec, customerMap);
    }
    CustomerRecord record = customerMap.get(customer);
    if (record == null)
    {
      // seed with the generic record for this customer
      record = new CustomerRecord(getCustomerRecordByPowerType(spec.getPowerType(), customer));
      customerMap.put(customer, record);
      // set up deferred activation in case this customer might do regulation
      record.setDeferredActivation();
    }
    return record;
  }

  /**
   * Finds the list of competing tariffs for the given PowerType.
   */
  List<TariffSpecification> getCompetingTariffs (PowerType powerType)
  {
    List<TariffSpecification> result = competingTariffs.get(powerType);
    if (result == null)
    {
      result = new ArrayList<TariffSpecification>();
      competingTariffs.put(powerType, result);
    }
    return result;
  }

  /**
   * Adds a new competing tariff to the list.
   */
  private void addCompetingTariff (TariffSpecification spec)
  {
    getCompetingTariffs(spec.getPowerType()).add(spec);
  }

  /**
   * Finds the list of own tariffs for the given PowerType.
   */
  List<TariffSpecification> getOwnTariffs (PowerType powerType)
  {
    List<TariffSpecification> result = ownTariffs.get(powerType);
    if (result == null) {
      result = new ArrayList<TariffSpecification>();
      ownTariffs.put(powerType, result);
    }
    return result;
  }

  /**
   * Adds a new own tariff to the list.
   */
  private void addOwnTariff(TariffSpecification spec)
  {
    List<TariffSpecification> tariffs = ownTariffs.get(spec.getPowerType());

    if(tariffs == null)
      tariffs = new ArrayList<>();

    tariffs.add(spec);
    ownTariffs.put(spec.getPowerType(), tariffs);
  }

  /**
   * Removes a old own tariff from the list.
   */
  private void removeOwnTariff(TariffSpecification spec)
  {
    List<TariffSpecification> tariffs = ownTariffs.get(spec.getPowerType());

    if(tariffs == null)
      return;

    tariffs.remove(spec);
    ownTariffs.put(spec.getPowerType(), tariffs);
  }

  public double[] getHourlyTariff(TariffSpecification specification)
  {
    // Generate tariff series
    List<Rate> rates = specification.getRates();

    double arr[] = new double[24];
    int flag1 = 0;

    for(Rate rate : rates)
    {
      int begin = rate.getDailyBegin();
      int end = rate.getDailyEnd() + 1;

      if(begin != -1)
      {
        flag1 = 1;
        while(begin != end)
        {
          arr[begin] = Math.abs(rate.getMinValue());
          begin = (begin + 1) % 24;
        }
      }
    }

    if(flag1 == 0)
      Arrays.fill(arr, Math.abs(rates.get(0).getMinValue()));

    return arr;
  }

  /**
   * Returns total usage for a given timeslot (represented as a simple index).
   */
  @Override
  public double collectUsage (int index)
  {
    double result = 0.0;
    for (Map<CustomerInfo, CustomerRecord> customerMap : customerSubscriptions.values())
    {
      for (CustomerRecord record : customerMap.values())
      {
        try
        {
          double usage = record.getUsage(index);
          result += usage;
        }
        catch(Exception e) {}
      }
    }
    return -result; // convert to needed energy account balance
  }

  // -------------- Message handlers -------------------
  /**
   * Handles CustomerBootstrapData by populating the customer model
   * corresponding to the given customer and power type. This gives the
   * broker a running start.
   */
   public synchronized void handleMessage (CustomerBootstrapData cbd)
   {
     CustomerInfo customer = customerRepo.findByNameAndPowerType(cbd.getCustomerName(), cbd.getPowerType());
     CustomerRecord record = getCustomerRecordByPowerType(cbd.getPowerType(), customer);
     int subs = record.subscribedPopulation;
     record.subscribedPopulation = customer.getPopulation();

     String custName = cbd.getCustomerName();

     for (int i = 0; i < cbd.getNetUsage().length; i++) {
       record.produceConsume(cbd.getNetUsage()[i], i);
       CustomerSubscriptionInformation customerSubscription = new CustomerSubscriptionInformation(custName,cbd.getPowerType(), customer.getPopulation(),customer.getPopulation());
       custUsageInfo.setCustomerSubscriptionList(i+this.OFFSET,customerSubscription);
       custUsageInfo.setCustomerSubscriptionMap(custName, i+this.OFFSET, customerSubscription);
     }
     record.subscribedPopulation = subs;
   }

  /**
   * Handles a TariffSpecification. These are sent by the server when new tariffs are
   * published. If it's not ours, then it's a competitor's tariff. We keep track of
   * competing tariffs locally, and we also store them in the tariffRepo.
   */
  public synchronized void handleMessage (TariffSpecification spec)
  {
    System.out.println("Broker : " + spec.getBroker().getUsername() + " :: Spec : " + spec.getPowerType() + " :: " + spec.getRates());

    if((spec.getBroker().getUsername().equals("default broker")) && (spec.getPowerType()==PowerType.CONSUMPTION))
    {
        dfrate=spec.getRates().get(0).getValue();
    }
    if((spec.getBroker().getUsername().equals("default broker")) && (spec.getPowerType()==PowerType.PRODUCTION))
    {
        dfrateProd=spec.getRates().get(0).getValue();
    }

    Broker theBroker = spec.getBroker();
    Integer currentTimeslot = timeslotRepo.currentTimeslot().getSerialNumber();

    if (brokerContext.getBrokerUsername().equals(theBroker.getUsername()))
    {
      if (theBroker != brokerContext.getBroker())
        // strange bug, seems harmless for now
        log.info("Resolution failed for broker " + theBroker.getUsername());
      // if it's ours, just log it, because we already put it in the repo
      TariffSpecification original = tariffRepo.findSpecificationById(spec.getId());
      if (null == original)
        log.error("Spec " + spec.getId() + " not in local repo");
      log.info("published " + spec);
    }
    else
    {
      // otherwise, keep track of competing tariffs, and record in the repo
      addCompetingTariff(spec);
      tariffRepo.addSpecification(spec);
    }
  }

  /**
   * Handles a TariffStatus message. This should do something when the status
   * is not SUCCESS.
   */
  public synchronized void handleMessage (TariffStatus ts)
  {
    log.info("TariffStatus: " + ts.getStatus());
  }

  /**
   * Handles a TariffTransaction. We only care about certain types: PRODUCE,
   * CONSUME, SIGNUP, and WITHDRAW.
   */
   public synchronized void handleMessage(TariffTransaction ttx)
   {
     int currentTimeslot = timeslotRepo.currentTimeslot().getSerialNumber();

     if((currentTimeslot%24 ==  0) && (!ttx.getCustomerInfo().getPowerType().isStorage()))
      System.out.println(currentTimeslot + " " + ttx.getTariffSpec().getId() + " ---" + ttx.getCustomerInfo().getName() + " " + ttx.getCustomerCount() + " " + ttx.getKWh() + " " + ttx.getCharge() + " " + (ttx.getCharge()/ttx.getKWh()));

     tariffMarketInformation.setTariffRevenueMap(currentTimeslot, ttx.getCharge());
     tariffMarketInformation.setTariffUsageMap(currentTimeslot, ttx.getKWh());

     if(ttx.getTxType() == TariffTransaction.Type.CONSUME)         // Store usage only for Consumption customers
     {
       tariffMarketInformation.setTariffConsumptionUsageMap(currentTimeslot, ttx.getKWh());
     }

     if(ttx.getTxType() == TariffTransaction.Type.PRODUCE)         // Store usage only for Production customers
     {
       tariffMarketInformation.setTariffProductionUsageMap(currentTimeslot, ttx.getKWh());
     }
      
     if((TariffTransaction.Type.CONSUME == ttx.getTxType()) || (TariffTransaction.Type.PRODUCE == ttx.getTxType()))
     {
       int hour = 0;
       int dayOfWeek = 0;
       int day = 0;
       Double usagePerPopulation = 0.0;
       Double chargePerUnit = 0.0;

       String customerName = ttx.getCustomerInfo().getName();
       PowerType pType = ttx.getCustomerInfo().getPowerType();
       TariffSpecification spec = ttx.getTariffSpec();
       Integer subscribedPopulation = ttx.getCustomerCount();
       Integer timeslot = ttx.getPostedTimeslotIndex();
       hour = timeslotRepo.findBySerialNumber(timeslot).getStartInstant().toDateTime().getHourOfDay();
       day = timeslotRepo.findBySerialNumber(timeslot).getStartInstant().toDateTime().getDayOfWeek();
       int blockNumber = Helper.getBlockNumber(hour, blocks);

       if(ttx.getKWh() != 0.0) {
         usagePerPopulation = Math.abs(ttx.getKWh() / subscribedPopulation);
         chargePerUnit =  Math.abs(ttx.getCharge() / ttx.getKWh());
       }
       UsageRecord usageRecord = new UsageRecord(hour, day, blockNumber, subscribedPopulation, chargePerUnit, spec.getId(), usagePerPopulation);

       custUsageInfo.setCustomerActualUsage(customerName, timeslot, usageRecord);

       int index = (ttx.getPostedTimeslotIndex() - 24) % brokerContext.getUsageRecordLength();
       custUsageInfo.setCustomerUsageProjectionMap(ttx.getCustomerInfo().getName(),index, usagePerPopulation);

       CustomerSubscriptionInformation customerSubscription = new CustomerSubscriptionInformation(customerName, pType, subscribedPopulation,ttx.getCustomerInfo().getPopulation());
       custUsageInfo.setCustomerSubscriptionList(timeslot,customerSubscription);
       custUsageInfo.setCustomerSubscriptionMap(customerName, timeslot, customerSubscription);

       custUsageInfo.setCustomerUsageMap(customerName, ttx.getPostedTimeslotIndex(), usagePerPopulation);
     }

     // make sure we have this tariff

     TariffSpecification newSpec = ttx.getTariffSpec();
     if (newSpec == null) {
       log.error("TariffTransaction type=" + ttx.getTxType() + " for unknown spec");
     }
     else {
       TariffSpecification oldSpec = tariffRepo.findSpecificationById(newSpec.getId());
       if (oldSpec != newSpec) {
         log.error("Incoming spec " + newSpec.getId() + " not matched in repo");
       }
     }
     TariffTransaction.Type txType = ttx.getTxType();
     CustomerRecord record = getCustomerRecordByTariff(ttx.getTariffSpec(), ttx.getCustomerInfo());


     if (TariffTransaction.Type.SIGNUP == txType) {
       // keep track of customer counts
       record.signup(ttx.getCustomerCount());
     }
     else if (TariffTransaction.Type.WITHDRAW == txType) {
       // customers presumably found a better deal
       record.withdraw(ttx.getCustomerCount());
     }
     else if (ttx.isRegulation()) {
       // Regulation transaction -- we record it as production/consumption
       // to avoid distorting the customer record.
       log.debug("Regulation transaction from {}, {} kWh for {}",
                 ttx.getCustomerInfo().getName(),
                 ttx.getKWh(), ttx.getCharge());
       record.produceConsume(ttx.getKWh(), ttx.getPostedTime());
     }
     else if (TariffTransaction.Type.PRODUCE == txType) {
       // if ttx count and subscribe population don't match, it will be hard
       // to estimate per-individual production
       if (ttx.getCustomerCount() != record.subscribedPopulation) {
         log.warn("production by subset {}  of subscribed population {}",
                  ttx.getCustomerCount(), record.subscribedPopulation);
       }

       record.produceConsume(ttx.getKWh(), ttx.getPostedTime());
     }
     else if (TariffTransaction.Type.CONSUME == txType) {
       if (ttx.getCustomerCount() != record.subscribedPopulation) {
         log.warn("consumption by subset {} of subscribed population {}",
                  ttx.getCustomerCount(), record.subscribedPopulation);
       }

       record.produceConsume(ttx.getKWh(), ttx.getPostedTime());
     }
   }

  /**
   * Handles a TariffRevoke message from the server, indicating that some
   * tariff has been revoked.
   */
  public synchronized void handleMessage (TariffRevoke tr)
  {
    Broker source = tr.getBroker();
    log.info("Revoke tariff " + tr.getTariffId() + " from " + tr.getBroker().getUsername());

    TariffSpecification old = tariffRepo.findSpecificationById(tr.getTariffId());

    List<TariffSpecification> candidates1 = ownTariffs.get(old.getPowerType());
    if (null == candidates1) {
      log.warn("Candidate list is null");
      return;
    }
    candidates1.remove(old);

    // if it's from some other broker, we need to remove it from the
    // tariffRepo, and from the competingTariffs list
    if (!(source.getUsername().equals(brokerContext.getBrokerUsername()))) {
      log.info("clear out competing tariff");
      TariffSpecification original = tariffRepo.findSpecificationById(tr.getTariffId());
      if (null == original) {
        log.warn("Original tariff " + tr.getTariffId() + " not found");
        return;
      }
      tariffRepo.removeSpecification(original.getId());
      List<TariffSpecification> candidates = competingTariffs.get(original.getPowerType());
      if (null == candidates) {
        log.warn("Candidate list is null");
        return;
      }
      candidates.remove(original);
    }
  }

  public synchronized void handleMessage (SimEnd se)
  {
    try
    {
      mabBasedDR.writeJSONtoFile(true);
    }
    catch(Exception e){}
  }

  @Override // from Activatable
  public synchronized void activate (int timeslotIndex)
  {
    /*if (customerSubscriptions.size() == 0)
    {
      int[] discount = mabBasedDR.getEstimatedAllocation();
      createMABDRTariffs(discount);
      System.out.println(mabBasedDR.toString());
    }
    else if((timeslotIndex-360) % 72 == 0)
    {
      double[] successProbs = checkSuccess(timeslotIndex);
      mabBasedDR.update(successProbs);

      List<TariffSpecification> consCandidates = ownTariffs.get(PowerType.CONSUMPTION);
      if(consCandidates != null)
      {
        List<TariffSpecification> tariffsToBeRemoved = new ArrayList<>();
        for(TariffSpecification spec: consCandidates)
            tariffsToBeRemoved.add(spec);

        for(TariffSpecification spec: tariffsToBeRemoved) // need to do after action selection, if not maintain, then revoke tariff
        {
          removeOwnTariff(spec);
          TariffRevoke revoke = new TariffRevoke(brokerContext.getBroker(), spec);
          customerSubscriptions.remove(spec);
          tariffRepo.removeSpecification(spec.getId());
          brokerContext.sendMessage(revoke);
        }
      }

      int[] discount = mabBasedDR.getEstimatedAllocation();
      createMABDRTariffs(discount);
      System.out.println(mabBasedDR.toString());
    }*/

    // updateMarketShare(timeslotIndex);       // Update broker's market share (volume)
    try
    {
      int nCustomers = 5;
      double[] U = new double[] {1000, 1200, 1000, 1500, 900};
      double[] UP = new double[] {400, 420, 350, 500, 300};
      double[] l = new double[] {0.2, 0.05, 0.1, 0.15, 0.01};
      double r = 2;
      double P = 10;

      String jsonData = JSON_API.communicateWithMatlab(9999, nCustomers, U, UP, l, r, P);
      // Parse JSON data
      ArrayList<Double> out = JSON_API.decodeJSON(jsonData);
      for(Double i: out)
        System.out.println(String.format("%.6f", i));
    }
    catch(Exception e) {e.printStackTrace();}

    /*try
    {
      if(timeslotIndex != 360)
        storetoMongoDB(timeslotIndex, discount);
    }
    catch(Exception e) { e.printStackTrace(); }

    if((timeslotIndex-360) % 720 == 0)
    {
      try
      {
        mabBasedDR.writeJSONtoFile(false);
      }
      catch(Exception e) {}
    }

    for (CustomerRecord record: notifyOnActivation)
      record.activate();*/
  }
  
  public void updateMarketShare(Integer timeslot)
  {
    distributionInformation = messageManager.getDistributionInformation();

    Double brokerNetUsageC = tariffMarketInformation.getTariffConsumptionUsage(timeslot);
    Double brokerNetUsageP = tariffMarketInformation.getTariffProductionUsage(timeslot);

    Double marketShareC = -1.0;
    Double marketShareP = -1.0;

    if(distributionInformation.getTotalConsumption(timeslot) != 0.0)
      marketShareC = Math.min(1.0, Math.abs(brokerNetUsageC / distributionInformation.getTotalConsumption(timeslot)));

    if(distributionInformation.getTotalProduction(timeslot) != 0.0)
      marketShareP = Math.min(1.0, Math.abs(brokerNetUsageP / distributionInformation.getTotalProduction(timeslot)));

    tariffMarketInformation.setMarketShareVolumeMapC(timeslot, marketShareC);
    tariffMarketInformation.setMarketShareVolumeMapP(timeslot, marketShareP);
  }

  public void storetoMongoDB(Integer timeslot, Double discount)
  {
    balancingMarketInformation = messageManager.getBalancingMarketInformation();
    marketTransactionInformation = messageManager.getMarketTransactionInformation();
    distributionInformation = messageManager.getDistributionInformation();
    cashPositionInformation = messageManager.getCashPositionInformation();
    gameInformation = messageManager.getGameInformation();
    capacityTransactionInformation = messageManager.getCapacityTransactionInformation();

    Double tariffRevenue = tariffMarketInformation.getTariffRevenue(timeslot);

    Double wholesaleCost = marketTransactionInformation.getBrokerWholesaleCost(timeslot);
    Double capacityTransactionPenalty = capacityTransactionInformation.getCapacityTransactionCharge(timeslot);

    Double balancingCost = 0.0;
    Double distributionCost = 0.0;

    try
    {
      distributionCost = distributionInformation.getDistributionTransaction(timeslot).getValue();
      balancingCost = balancingMarketInformation.getBalancingTransaction(timeslot).getValue();
    }
    catch(Exception e){}

    Double cashBalance = cashPositionInformation.getCashPosition(timeslot);
    Double bankInterest = cashPositionInformation.getBankInterest(timeslot);

    Double profit = tariffRevenue + wholesaleCost + balancingCost + distributionCost + capacityTransactionPenalty + bankInterest;

    Double incomeToCostRatio = -1.0;

    if(wholesaleCost != 0.0)
        incomeToCostRatio = Math.abs(tariffRevenue/ wholesaleCost);

    Double marketShareC = tariffMarketInformation.getMarketShareVolumeMapC(timeslot);
    Double marketShareP = tariffMarketInformation.getMarketShareVolumeMapP(timeslot);

    try
    {
      String col1 = "AccountingInformation";
      DBCollection collection1 = mongoDatabase.getCollection(col1);

      DBObject document1 = new BasicDBObject();

      document1.put("Game_Name", gameInformation.getName());
      document1.put("Timeslot", timeslot);
      document1.put("Tariff_Label", discount);
      document1.put("Income_to_Cost_Ratio", incomeToCostRatio);
      document1.put("Market_ShareC", marketShareC);
      document1.put("Market_ShareP", marketShareP);
      document1.put("Tariff_Revenue", tariffRevenue);
      document1.put("Wholesale_Cost", wholesaleCost);
      document1.put("Balancing_Cost", balancingCost);
      document1.put("Distribution_Cost", distributionCost);
      document1.put("Capacity_Transaction", capacityTransactionPenalty);
      document1.put("Profit", profit);
      document1.put("Cash_Position", cashBalance);

      collection1.insert(document1);
    }
    catch(Exception e){e.printStackTrace();}
  }

  private void createMABDRTariffs(int[] discount)
  {
    double[] trueDiscount = new double[discount.length];
    for(int i = 0; i < discount.length; i++)
    {
      System.out.println("Discount: " + discount[i]);
      trueDiscount[i] = discount[i]*discountMultiplier;
    }

    double marketPrice = marketManager.getMeanMarketPrice() / 1000.0;
    benchmarkPrice = ((marketPrice + fixedPerKwh) * (1.0 + defaultMargin));

    int nPeaks = 9;  // peakPrice in blockStructure
    
    // Prices for Group-1 Tariff
    Double x1 = 168*benchmarkPrice / (7 * ((24 - nPeaks)*(1 - trueDiscount[0]) + nPeaks));
    double peakPrice1 = x1;
    double nonpeakPrice1 = x1*(1 - trueDiscount[0]);  

    Double[] blockStructure1 = new Double[] {nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, 
                                            peakPrice1, peakPrice1, peakPrice1, peakPrice1, nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, nonpeakPrice1, 
                                            nonpeakPrice1, nonpeakPrice1, peakPrice1, peakPrice1, peakPrice1, nonpeakPrice1, peakPrice1, peakPrice1, nonpeakPrice1};

    // Prices for Group-2 Tariff
    Double x2 = 168*benchmarkPrice / (7 * ((24 - nPeaks)*(1 - trueDiscount[1]) + nPeaks));
    double peakPrice2 = x2;
    double nonpeakPrice2 = x2*(1 - trueDiscount[1]);  

    Double[] blockStructure2 = new Double[] {nonpeakPrice2, nonpeakPrice2, nonpeakPrice2, nonpeakPrice2, nonpeakPrice2, peakPrice2, peakPrice2, 
                                            nonpeakPrice2, nonpeakPrice2, peakPrice2, peakPrice2, peakPrice2, peakPrice2, peakPrice2, peakPrice2, 
                                            peakPrice2, nonpeakPrice2, nonpeakPrice2, peakPrice2, peakPrice2, peakPrice2, nonpeakPrice2, nonpeakPrice2, nonpeakPrice2};

    // Prices for Group-3 Tariff
    Double x3 = 168*benchmarkPrice / (7 * ((24 - nPeaks)*(1 - trueDiscount[2]) + nPeaks));
    double peakPrice3 = x3;
    double nonpeakPrice3 = x3*(1 - trueDiscount[2]);  

    Double[] blockStructure3 = new Double[] {nonpeakPrice3, nonpeakPrice3, nonpeakPrice3, nonpeakPrice3, nonpeakPrice3, peakPrice3, peakPrice3, 
                                            nonpeakPrice3, nonpeakPrice3, peakPrice3, peakPrice3, peakPrice3, peakPrice3, peakPrice3, peakPrice3, 
                                            peakPrice3, nonpeakPrice3, nonpeakPrice3, peakPrice3, peakPrice3, peakPrice3, nonpeakPrice3, nonpeakPrice3, nonpeakPrice3};

    // Prices for Group-4 Tariff
    Double x4 = 168*benchmarkPrice / (7 * ((24 - nPeaks)*(1 - trueDiscount[3]) + nPeaks));
    double peakPrice4 = x4;
    double nonpeakPrice4 = x4*(1 - trueDiscount[3]);  

    Double[] blockStructure4 = new Double[] {nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, 
                                            peakPrice4, peakPrice4, peakPrice4, peakPrice4, nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, nonpeakPrice4, 
                                            nonpeakPrice4, nonpeakPrice4, peakPrice4, peakPrice4, peakPrice4, nonpeakPrice4, peakPrice4, peakPrice4, nonpeakPrice4};

    ///////////// Publish Tariffs //////////////////

    /** For BrooksideHomes and CentervilleHomes */

    TariffSpecification spec1 = new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION);

    for(int j = 0; j < 24; j++)
    {
        Double rateValue = blockStructure1[j];
        Rate rate = new Rate().withValue(rateValue).withDailyBegin(j).withDailyEnd(j).withTierThreshold(0.0);

        spec1.addRate(rate);
    }
    Rate rate12 = new Rate().withValue(100*benchmarkPrice).withTierThreshold(20.0);
    spec1.addRate(rate12);

    addOwnTariff(spec1);
    customerSubscriptions.put(spec1, new LinkedHashMap<>());
    tariffRepo.addSpecification(spec1);
    brokerContext.sendMessage(spec1);

    /** For DowntownOffices and EastsideOffices */
    TariffSpecification spec2 = new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION);

    Rate rate21 = new Rate().withValue(50*benchmarkPrice).withTierThreshold(0.0);
    for(int j = 0; j < 24; j++)
    {
        Double rateValue = blockStructure2[j];
        Rate rate = new Rate().withValue(rateValue).withDailyBegin(j).withDailyEnd(j).withTierThreshold(20.0);

        spec2.addRate(rate);
    }
    Rate rate23 = new Rate().withValue(25*benchmarkPrice).withTierThreshold(10000.0);
    spec2.addRate(rate21);
    spec2.addRate(rate23);

    addOwnTariff(spec2);
    customerSubscriptions.put(spec2, new LinkedHashMap<>());
    tariffRepo.addSpecification(spec2);
    brokerContext.sendMessage(spec2);

    /** For MedicalCenter-1 */
    TariffSpecification spec3 = new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION);

    Rate rate31 = new Rate().withValue(25*benchmarkPrice).withTierThreshold(0.0);
    for(int j = 0; j < 24; j++)
    {
        Double rateValue = blockStructure3[j];
        Rate rate = new Rate().withValue(rateValue).withDailyBegin(j).withDailyEnd(j).withTierThreshold(2000.0);

        spec3.addRate(rate);
    }
    spec3.addRate(rate31);

    addOwnTariff(spec3);
    customerSubscriptions.put(spec3, new LinkedHashMap<>());
    tariffRepo.addSpecification(spec3);
    brokerContext.sendMessage(spec3);

    /** For HextraChemical */
    TariffSpecification spec4 = new TariffSpecification(brokerContext.getBroker(), PowerType.CONSUMPTION);

    Rate rate41 = new Rate().withValue(50*benchmarkPrice).withTierThreshold(0.0);
    for(int j = 0; j < 24; j++)
    {
        Double rateValue = blockStructure4[j];
        Rate rate = new Rate().withValue(rateValue).withDailyBegin(j).withDailyEnd(j).withTierThreshold(200.0);

        spec4.addRate(rate);
    }
    Rate rate43 = new Rate().withValue(25*benchmarkPrice).withTierThreshold(30000.0);

    spec4.addRate(rate41);
    spec4.addRate(rate43);

    addOwnTariff(spec4);
    customerSubscriptions.put(spec4, new LinkedHashMap<>());
    tariffRepo.addSpecification(spec4);
    brokerContext.sendMessage(spec4);
  }

  public double[] checkSuccess(Integer currentTimeslot)
  {
    Map<String, Map<Integer, Double>> peakData = mabBasedDR.getPeakData();

    Map<Integer, Double> brooksideHomesPeaks = peakData.get("BrooksideHomes");
    Map<Integer, Double> centervilleHomesPeaks = peakData.get("CentervilleHomes");
    Map<Integer, Double> downtownOfficesPeaks = peakData.get("DowntownOffices");
    Map<Integer, Double> eastsideOfficesPeaks = peakData.get("EastsideOffices");
    Map<Integer, Double> hextraChemicalPeaks = peakData.get("HextraChemical");
    Map<Integer, Double> medicalCenterPeaks = peakData.get("MedicalCenter-1");

    double group1Prob = Math.max(0.0, (getAveragePeakUsageInformation("BrooksideHomes", currentTimeslot, brooksideHomesPeaks) + 
                         getAveragePeakUsageInformation("CentervilleHomes", currentTimeslot, centervilleHomesPeaks)) / 2);

    double group2Prob = Math.max(0.0,(getAveragePeakUsageInformation("DowntownOffices", currentTimeslot, downtownOfficesPeaks) + 
                         getAveragePeakUsageInformation("EastsideOffices", currentTimeslot, eastsideOfficesPeaks)) / 2);

    double group3Prob = Math.max(0.0,getAveragePeakUsageInformation("HextraChemical", currentTimeslot, hextraChemicalPeaks));

    double group4Prob = Math.max(0.0,getAveragePeakUsageInformation("MedicalCenter-1", currentTimeslot, medicalCenterPeaks));
    System.out.println(group1Prob + ", " + group2Prob + ", "  + group3Prob + ", " + group4Prob);

    return new double[] {group1Prob, group2Prob, group3Prob, group4Prob};
  }

  public double getAveragePeakUsageInformation(String custName, Integer currentTimeslot, Map<Integer, Double> custNamePeaks)
  {
    Map<String, Map<Integer, UsageRecord>> consumptionInfoMap = custUsageInfo.getCustomerAcutalUsageMap();
    Map<Integer, UsageRecord> customerRecord = consumptionInfoMap.get(custName);

    System.out.println(custName);
    Double reducedAvgUsageGroup = 0.0;
    for(Map.Entry<Integer, Double> item: custNamePeaks.entrySet())
    {
      Integer peak = item.getKey();
      Double originalAvgUsage = item.getValue();

      Double reducedAvgUsageCust = 0.0;

      int count = 0;

      if(customerRecord.get(currentTimeslot + peak - 24) != null)
      {
        reducedAvgUsageCust += customerRecord.get(currentTimeslot + peak - 24).getConsumptionPerPopulation();
        count++;
      }

      if(customerRecord.get(currentTimeslot + peak - 48) != null)
      {
        reducedAvgUsageCust += customerRecord.get(currentTimeslot + peak - 48).getConsumptionPerPopulation();
        count++;
      }

      if(customerRecord.get(currentTimeslot + peak - 72) != null)
      {
        reducedAvgUsageCust += customerRecord.get(currentTimeslot + peak - 72).getConsumptionPerPopulation();
      }

      if(count != 0)
        reducedAvgUsageCust /= count;
      else
        reducedAvgUsageCust = originalAvgUsage;

      reducedAvgUsageGroup += (originalAvgUsage - reducedAvgUsageCust) / originalAvgUsage;

      System.out.println(originalAvgUsage +  " :: " + reducedAvgUsageCust + " :: " + (originalAvgUsage - reducedAvgUsageCust) / originalAvgUsage);
    }
    reducedAvgUsageGroup /= custNamePeaks.size();

    return reducedAvgUsageGroup;
  }

  // ------------- test-support methods ----------------
  double getUsageForCustomer (CustomerInfo customer,
                              TariffSpecification tariffSpec,
                              int index)
  {
    CustomerRecord record = getCustomerRecordByTariff(tariffSpec, customer);
    return record.getUsage(index);
  }

  // test-support method
  HashMap<PowerType, double[]> getRawUsageForCustomer (CustomerInfo customer)
  {
    HashMap<PowerType, double[]> result = new HashMap<>();
    for (PowerType type : customerProfiles.keySet()) {
      CustomerRecord record = customerProfiles.get(type).get(customer);
      if (record != null) {
        result.put(type, record.usage);
      }
    }
    return result;
  }

  // test-support method
  HashMap<String, Integer> getCustomerCounts()
  {
    HashMap<String, Integer> result = new HashMap<>();
    for (TariffSpecification spec : customerSubscriptions.keySet()) {
      Map<CustomerInfo, CustomerRecord> customerMap = customerSubscriptions.get(spec);
      for (CustomerRecord record : customerMap.values()) {
        result.put(record.customer.getName() + spec.getPowerType(),
                    record.subscribedPopulation);
      }
    }
    return result;
  }

  //-------------------- Customer-model recording ---------------------
  /**
   * Keeps track of customer status and usage. Usage is stored
   * per-customer-unit, but reported as the product of the per-customer
   * quantity and the subscribed population. This allows the broker to use
   * historical usage data as the subscribed population shifts.
   */
  class CustomerRecord
  {
    CustomerInfo customer;
    int subscribedPopulation = 0;
    double[] usage;
    double alpha = 0.3;
    boolean deferredActivation = false;
    double deferredUsage = 0.0;
    int savedIndex = 0;

    /**
     * Creates an empty record
     */
    CustomerRecord (CustomerInfo customer)
    {
      super();
      this.customer = customer;
      this.usage = new double[brokerContext.getUsageRecordLength()];
    }

    CustomerRecord (CustomerRecord oldRecord)
    {
      super();
      this.customer = oldRecord.customer;
      this.usage = Arrays.copyOf(oldRecord.usage, brokerContext.getUsageRecordLength());
    }

    // Returns the CustomerInfo for this record
    CustomerInfo getCustomerInfo ()
    {
      return customer;
    }

    // Adds new individuals to the count
    void signup (int population)
    {
      subscribedPopulation = Math.min(customer.getPopulation(), subscribedPopulation + population);
    }

    // Removes individuals from the count
    void withdraw (int population)
    {
      subscribedPopulation -= population;
    }

    // Sets up deferred activation
    void setDeferredActivation ()
    {
      deferredActivation = true;
      notifyOnActivation.add(this);
    }

    // Customer produces or consumes power. We assume the kwh value is negative
    // for production, positive for consumption
    void produceConsume (double kwh, Instant when)
    {
      int index = getIndex(when);
      produceConsume(kwh, index);
    }

    // stores profile data at the given index
    void produceConsume (double kwh, int rawIndex)
    {
      if (deferredActivation) {
        deferredUsage += kwh;
        savedIndex = rawIndex;
      }
      else
        localProduceConsume(kwh, rawIndex);
    }

    // processes deferred recording to accomodate regulation
    void activate ()
    {
      //PortfolioManagerService.log.info("activate {}", customer.getName());
      localProduceConsume(deferredUsage, savedIndex);
      deferredUsage = 0.0;
    }

    private void localProduceConsume (double kwh, int rawIndex)
    {
      int index = getIndex(rawIndex);
      double kwhPerCustomer = 0.0;
      if (subscribedPopulation > 0) {
        kwhPerCustomer = kwh / (double)subscribedPopulation;
      }
      double oldUsage = usage[index];
      if (oldUsage == 0.0) {
        // assume this is the first time
        usage[index] = kwhPerCustomer;
      }
      else {
        // exponential smoothing
        usage[index] = alpha * kwhPerCustomer + (1.0 - alpha) * oldUsage;
      }
      //PortfolioManagerService.log.debug("consume {} at {}, customer {}", kwh, index, customer.getName());
    }

    double getUsage (int index)
    {
      if (index < 0) {
        PortfolioManagerService.log.warn("usage requested for negative index " + index);
        index = 0;
      }
      return (usage[getIndex(index)] * (double)subscribedPopulation);
    }

    // we assume here that timeslot index always matches the number of
    // timeslots that have passed since the beginning of the simulation.
    int getIndex (Instant when)
    {
      int result = (int)((when.getMillis() - timeService.getBase()) /
                         (Competition.currentCompetition().getTimeslotDuration()));
      return result;
    }

    private int getIndex (int rawIndex)
    {
      return rawIndex % usage.length;
    }
  }
}
