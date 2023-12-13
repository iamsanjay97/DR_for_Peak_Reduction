package org.powertac.samplebroker.util;

import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;

import javafx.util.Pair;

import java.io.FileReader;
import java.io.PrintWriter;
import java.io.FileNotFoundException;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class MABBasedDR {

    public int budget;
    public int numberOfUsers;
    public int numberOfDiscounts;

    public int[][] offeredHist; 
    public double[][] successHist;

    public double[] K_estimate;
    public double[] K_estimatePlus;
                    
    public int t;

    public Map<String, Map<Integer, Double>> peakData = new HashMap<>();

    public MABBasedDR(int budget, int numberOfUsers)
    {
        this.budget = budget;
        this.numberOfUsers = numberOfUsers;
        this.numberOfDiscounts = budget;

        this.offeredHist = new int[numberOfUsers][numberOfDiscounts+1];
        this.successHist = new double[numberOfUsers][numberOfDiscounts+1];

        this.K_estimate = new double[numberOfUsers];
        this.K_estimatePlus = new double[numberOfUsers];

        Map<Integer, Double> peakData1 = new HashMap<>();
        peakData1.put(7, 2.0197);
        peakData1.put(17, 1.8222);
        this.peakData.put("BrooksideHomes", peakData1);

        Map<Integer, Double> peakData2 = new HashMap<>();
        peakData2.put(20, 1.7033);
        peakData2.put(19, 1.6384);
        this.peakData.put("CentervilleHomes", peakData2);

        Map<Integer, Double> peakData3 = new HashMap<>();
        peakData3.put(15, 409.6350);
        peakData3.put(14, 399.0247);
        this.peakData.put("DowntownOffices", peakData3);

        Map<Integer, Double> peakData4 = new HashMap<>();
        peakData4.put(12, 426.6576);
        peakData4.put(9, 415.0484);
        this.peakData.put("EastsideOffices", peakData4);

        Map<Integer, Double> peakData5 = new HashMap<>();
        peakData5.put(23, 935.9980);
        peakData5.put(13, 935.5640);
        this.peakData.put("HextraChemical", peakData5);

        Map<Integer, Double> peakData6 = new HashMap<>();
        peakData6.put(14, 8046.99);
        peakData6.put(18, 7991.73);
        this.peakData.put("MedicalCenter-1", peakData6);

        try
        {
            readJSONfromFile();
            System.out.println("Data loaded from file !!!");
        }
        catch(Exception e)
        {
            System.out.println("Error in loading JSON file !!!");

            Arrays.fill(this.K_estimate, 0, this.numberOfUsers, 0.01);
            Arrays.fill(this.K_estimatePlus, 0, this.numberOfUsers, 0.01);

            this.t = 0;
        }
    }

    public Map<String, Map<Integer, Double>> getPeakData()
    {
        return this.peakData;
    }

    public int[] getEstimatedAllocation()
    {
        return offline(this.budget, this.numberOfUsers, this.K_estimatePlus);
    }

    public void update(double[] probabilities)
    {
        int[] C_estimates = new int[numberOfUsers];   
        C_estimates = offline(this.budget, this.numberOfUsers, this.K_estimatePlus);

        int[][] offeredInst = new int[numberOfUsers][numberOfDiscounts+1]; 
        double[][] successInst = new double[numberOfUsers][numberOfDiscounts+1];

        for(int j = 0; j < this.numberOfUsers; j++)
        {
            if(C_estimates[j] != 0)
            {
                offeredInst[j][C_estimates[j]] += 1;
                successInst[j][C_estimates[j]] += probabilities[j];

                this.offeredHist[j][C_estimates[j]] += offeredInst[j][C_estimates[j]];
                this.successHist[j][C_estimates[j]] += successInst[j][C_estimates[j]];
            }
        }

        this.t++;
        Pair<double[], double[]> output = estimateLambdaLS(this.offeredHist, this.successHist, this.numberOfUsers, this.numberOfDiscounts, this.t);
    
        this.K_estimate = output.getKey();        
        this.K_estimatePlus = output.getValue();
    }
    
    public static int[] offline(int budget, int numberOfUsers, double[] K)
    {
        /**
         * Weightage of each group of customers based on their cumulative usage
         * Group1: ["BrooksideHomes", "CentervilleHomes"]: 50%
         * Group2: ["DowntownOffices", "EastsideOffices"]: 25%
         * Group3: ["MedicalCenter-1"]: 12.5%
         * Group4: ["HextraChemical"]: 12.5%
         */
        int cost = 0;           // initial DR cost
        int quantum = 1;        // initial quantum

        int[] allocation = new int[numberOfUsers];
        int[] weightedAllocation = new int[numberOfUsers];
        int[] weights = new int[] {4, 2, 1, 1};

        while(cost < budget)
        {
            int d = 0;
            int largestIndex = 0;

            while(d < numberOfUsers)
            {
                if(jump(K[d], allocation[d]) >= jump(K[largestIndex], allocation[largestIndex]))
                    largestIndex = d;

                d += 1;
            }

            allocation[largestIndex] += quantum;
            weightedAllocation[largestIndex] += Math.min(weights[largestIndex]*quantum, budget-cost);
            cost += Math.min(weights[largestIndex]*quantum, budget-cost);
        }

        return weightedAllocation;
    }

    public static Pair<double[], double[]> estimateLambdaLS(int[][] offeredHist, double[][] successHist, int numberOfUsers, int numberOfDiscounts, int t)
    {
        double[] prob = new double[numberOfDiscounts+1];   // shouldn't be zeros
        double[] lambdaEstimate = new double[numberOfUsers];
        double[] lambdaPlus = new double[numberOfUsers];

        Arrays.fill(prob, 0, numberOfDiscounts+1, 1);
        Arrays.fill(lambdaEstimate, 0, numberOfUsers, 1);
        Arrays.fill(lambdaPlus, 0, numberOfUsers, 1);
        
        for(int i = 0; i < numberOfUsers; i++)
        {
            double lambdaMax = 0.0;
            double lambdaMin = 5.0;
            double lambdaEstimate_i[] = new double[numberOfDiscounts+1]; 
            Arrays.fill(lambdaEstimate_i, 0, numberOfDiscounts+1, 0.6);

            for(int j = 1; j <= numberOfDiscounts; j++)
            {
                if(offeredHist[i][j] !=0)
                {
                    prob[j]             = successHist[i][j]/offeredHist[i][j];
                    lambdaEstimate_i[j] = Math.max(0.00000001, -Math.log(1-prob[j]+0.00000001)/j);
                
                    if(lambdaEstimate_i[j] > lambdaMax)
                        lambdaMax = lambdaEstimate_i[j];

                    if(lambdaEstimate_i[j] < lambdaMin)
                        lambdaMin = lambdaEstimate_i[j];
                }
            }
        
            double err = 500000000.0; 
            	
            for(double k = lambdaMin; k <= lambdaMax; k+=0.00005)
            {
                double tempErr = 0.0;

                for(int j = 1; j <= numberOfDiscounts; j++)
                    tempErr += ((prob[j] - (1-Math.exp(-k*j))))*((prob[j] - (1-Math.exp(-k*j))));

                if(tempErr < err)
                {
                    err = tempErr;
                    lambdaEstimate[i] = k;
                }
            }

            int offered_i = 0;
            for(int j = 1; j <= numberOfDiscounts; j++)
                offered_i += offeredHist[i][j];

            lambdaPlus[i] = lambdaEstimate[i] + Math.sqrt(2*Math.log(t)/(offered_i));
        }

        return new Pair<double[], double[]> (lambdaEstimate, lambdaPlus);
    }

    public static double jump(double k, double c)
    {   
        return ((1 - Math.exp(-k*(c+1))) - (1 - Math.exp(-k*c)));
    }

    public void writeJSONtoFile(boolean flag) throws FileNotFoundException 
    {
        JSONObject jsonObject = new JSONObject();

        jsonObject.put("Budget", budget);
        jsonObject.put("Number of Users", numberOfUsers);
        jsonObject.put("Number of Discounts", numberOfDiscounts);
        jsonObject.put("Transitions", this.t);
          
        List<Double> list1 = new ArrayList<>();
        for(int i = 0; i < this.K_estimate.length; i++)
            list1.add(this.K_estimate[i]);
        jsonObject.put("Estimated K", list1);

        List<Double> list2 = new ArrayList<>();
        for(int i = 0; i < this.K_estimatePlus.length; i++)
            list2.add(this.K_estimatePlus[i]);
        jsonObject.put("Estimated K UCB", list2);

        List<List<Integer>> mat1 = new ArrayList<>();
        for(int i = 0; i < this.offeredHist.length; i++)
        {
            List<Integer> temp = new ArrayList<>();
            for(int j = 0; j < this.offeredHist[0].length; j++)
            {
                temp.add(this.offeredHist[i][j]);
            }
            mat1.add(temp);
        }
        jsonObject.put("Offered History", mat1);

        List<List<Double>> mat2 = new ArrayList<>();
        for(int i = 0; i < this.successHist.length; i++)
        {
            List<Double> temp = new ArrayList<>();
            for(int j = 0; j < this.successHist[0].length; j++)
            {
                temp.add(this.successHist[i][j]);
            }
            mat2.add(temp);
        }
        jsonObject.put("Success History", mat2);
          
        String name = "MABBasedDR";
        if(flag)
            name += ".json";
        else
            name += Integer.toString(this.t) + ".json";
        PrintWriter pw = new PrintWriter(name);
        pw.write(jsonObject.toJSONString());
          
        pw.flush();
        pw.close();
    }

    public void readJSONfromFile() throws Exception 
    {
        Object obj = new JSONParser().parse(new FileReader("MABBasedDR.json"));
          
        JSONObject jsonObject = (JSONObject) obj;
          
        this.budget = Integer.parseInt(jsonObject.get("Budget").toString());
        this.numberOfUsers = Integer.parseInt(jsonObject.get("Number of Users").toString());
        this.numberOfDiscounts = Integer.parseInt(jsonObject.get("Number of Discounts").toString());
        this.t = Integer.parseInt(jsonObject.get("Transitions").toString());

        JSONParser parser = new JSONParser();
		JSONArray json = null;

        int index = 0;
		try
		{
			 json = (JSONArray) parser.parse(jsonObject.get("Estimated K").toString());
			 for(Object a : json)
             {
                this.K_estimate[index++] = Double.valueOf(a.toString());
             }
		}
		catch(Exception e){e.printStackTrace();}

        index = 0;
        try
		{
			 json = (JSONArray) parser.parse(jsonObject.get("Estimated K UCB").toString());
			 for(Object a : json)
                this.K_estimatePlus[index++] = Double.valueOf(a.toString());
		}
		catch(Exception e){}

        int outerIndex = 0;
        int innerIndex = 0;
        try
		{
			 json = (JSONArray) parser.parse(jsonObject.get("Offered History").toString());
			 for(Object a : json)
             {
                JSONArray temp = (JSONArray) parser.parse(a.toString());
                for(Object b : temp)
                    this.offeredHist[outerIndex][innerIndex++] = Integer.valueOf(b.toString());
             }
             outerIndex++;
		}
		catch(Exception e){}

        outerIndex = 0;
        innerIndex = 0;
        try
		{
			 json = (JSONArray) parser.parse(jsonObject.get("Success History").toString());
			 for(Object a : json)
             {
                JSONArray temp = (JSONArray) parser.parse(a.toString());
                for(Object b : temp)
                    this.successHist[outerIndex][innerIndex++] = Double.valueOf(b.toString());
             }
             outerIndex++;
		}
		catch(Exception e){}
    }

    public String toString()
    {
        String S = "";

        S += "Estimated K:\n";
        for(int i = 0; i < this.K_estimate.length; i++)
            S += this.K_estimate[i] + " ";
        S += "\n\n";

        S += "Estimated K UCB:\n";
        for(int i = 0; i < this.K_estimatePlus.length; i++)
            S += this.K_estimatePlus[i] + " ";
            S += "\n\n";

        S += "Offered History:\n";
        for(int i = 0; i < this.offeredHist.length; i++)
        {
            for(int j = 0; j < this.offeredHist[0].length; j++)
                S += this.offeredHist[i][j] + " ";
            S += "\n";
        }
        S += "\n\n";

        S += "Success History\n";
        for(int i = 0; i < this.successHist.length; i++)
        {
            for(int j = 0; j < this.successHist[0].length; j++)
                S += this.successHist[i][j] + " ";
            S += "\n";
        }
        S += "\n\n";

        return S;
    }
}
