package org.powertac.samplebroker.information;

public class UsageRecord {

    int timeOfday;
    int dayOfWeek;
    int blockNumber;
    int subscribedPopulation;
    double unitTariff;
    long tariffID;
    double consumptionPerPopulation;

    UsageRecord(){}

    public UsageRecord(int timeOfday, int dayOfWeek, int blockNumber, int subscribedPopulation, double unitTariff, long tariffID, double consumptionPerPopulation){
        this.timeOfday = timeOfday;
        this.dayOfWeek = dayOfWeek;
        this.blockNumber = blockNumber;
        this.unitTariff = unitTariff;
        this.tariffID = tariffID;
        this.subscribedPopulation = subscribedPopulation;
        this.consumptionPerPopulation = consumptionPerPopulation;
    }

    public int getTimeOfDay(){
        return timeOfday;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public int getBlockNumber(){
        return blockNumber;
    }

    public double getUnitTariff(){
        return unitTariff;
    }

    public long getTariffID(){
        return tariffID;
    }

    public int getSubscribedPopulation(){
        return subscribedPopulation;
    }

    public double getConsumptionPerPopulation(){
        return consumptionPerPopulation;
    }

    public String toString(){

        return Integer.toString(timeOfday) + ", " + Integer.toString(dayOfWeek) + ", " + Integer.toString(blockNumber) + ", " + Integer.toString(subscribedPopulation) + ", " + Double.toString(unitTariff) + ", " +    Long.toString(tariffID) + ", " + Double.toString(consumptionPerPopulation);
    }

}
