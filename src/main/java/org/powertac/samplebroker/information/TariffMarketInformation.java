package org.powertac.samplebroker.information;

import java.util.HashMap;
import java.util.Map;

public class TariffMarketInformation
{
    private Map<Integer, Double> tariffRevenueMap;
    private Map<Integer, Double> tariffUsageMap;
    private Map<Integer, Double> tariffConsumptionUsageMap;
    private Map<Integer, Double> tariffProductionUsageMap;
    private Map<Integer, Double> marketShareVolumeC;
    private Map<Integer, Double> marketShareVolumeP;
    private Map<Integer, Double> marketShareCustomer;

    public TariffMarketInformation()
    {
        tariffRevenueMap = new HashMap<>();
        tariffUsageMap = new HashMap<>();
        tariffConsumptionUsageMap = new HashMap<>();
        tariffProductionUsageMap = new HashMap<>();
        marketShareVolumeC = new HashMap<>();
        marketShareVolumeP = new HashMap<>();
        marketShareCustomer = new HashMap<>();
    }

    public void setTariffRevenueMap(Integer timeslot, Double price) 
    {
      if(tariffRevenueMap.get(timeslot) == null)  
        tariffRevenueMap.put(timeslot, 0.0);

      Double revenue = price + tariffRevenueMap.get(timeslot);

      tariffRevenueMap.put(timeslot, revenue);
    }

    public Double getTariffRevenue(Integer timeslot)
    {
      if(tariffRevenueMap.get(timeslot) != null)
        return tariffRevenueMap.get(timeslot);
      else
        return 0.0;
    }

    public void setTariffUsageMap(Integer timeslot, Double usage) 
    {
      if(tariffUsageMap.get(timeslot) == null)  
        tariffUsageMap.put(timeslot, 0.0);

      Double revenue = usage + tariffUsageMap.get(timeslot);

      tariffUsageMap.put(timeslot, revenue);
    }

    public Double getTariffUsage(Integer timeslot)
    {
      if(tariffUsageMap.get(timeslot) != null)
        return tariffUsageMap.get(timeslot);
      else
        return 0.0;
    }

    public void setTariffConsumptionUsageMap(Integer timeslot, Double usage) 
    {
      if(tariffConsumptionUsageMap.get(timeslot) == null)  
        tariffConsumptionUsageMap.put(timeslot, 0.0);

      Double revenue = usage + tariffConsumptionUsageMap.get(timeslot);

      tariffConsumptionUsageMap.put(timeslot, revenue);
    }

    public Double getTariffConsumptionUsage(Integer timeslot)
    {
      if(tariffConsumptionUsageMap.get(timeslot) != null)
        return tariffConsumptionUsageMap.get(timeslot);
      else
        return 0.0;
    }

    public void setTariffProductionUsageMap(Integer timeslot, Double usage) 
    {
      if(tariffProductionUsageMap.get(timeslot) == null)  
        tariffProductionUsageMap.put(timeslot, 0.0);

      Double revenue = usage + tariffProductionUsageMap.get(timeslot);

      tariffProductionUsageMap.put(timeslot, revenue);
    }

    public Double getTariffProductionUsage(Integer timeslot)
    {
      if(tariffProductionUsageMap.get(timeslot) != null)
        return tariffProductionUsageMap.get(timeslot);
      else
        return 0.0;
    }

    public void setMarketShareVolumeMapC(Integer timeslot, Double marketShare) 
    {
      marketShareVolumeC.put(timeslot, marketShare);
    }

    public Double getMarketShareVolumeMapC(Integer timeslot)
    {
      if(marketShareVolumeC.get(timeslot) != null)
        return marketShareVolumeC.get(timeslot);
      else
        return 0.0;
    }

    public void setMarketShareVolumeMapP(Integer timeslot, Double marketShare) 
    {
      marketShareVolumeP.put(timeslot, marketShare);
    }

    public Double getMarketShareVolumeMapP(Integer timeslot)
    {
      if(marketShareVolumeP.get(timeslot) != null)
        return marketShareVolumeP.get(timeslot);
      else
        return 0.0;
    }

    public void setMarketShareCustomerMap(Integer timeslot, Double marketShare) 
    {
      marketShareCustomer.put(timeslot, marketShare);
    }

    public Double getMarketShareCustomerMap(Integer timeslot)
    {
      if(marketShareCustomer.get(timeslot) != null)
        return marketShareCustomer.get(timeslot);
      else
        return 0.0;
    }
}
