package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/** Computes ten statistical and temporal features from cleaned SGCC daily consumption sequences. */
public final class FeatureExtractor {
    private FeatureExtractor() {}
    public static Path extract(Path cleaned, Path output) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        CSVFormat format=CSVFormat.DEFAULT;
        try(Reader reader=Files.newBufferedReader(cleaned,StandardCharsets.UTF_8);
            CSVParser parser=format.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader);
            Writer writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8);
            CSVPrinter csv=new CSVPrinter(writer,format)) {
            Map<String,Integer> cols=parser.getHeaderMap();
            if(!cols.containsKey("CONS_NO")||!cols.containsKey("FLAG"))throw new IOException("Missing CONS_NO or FLAG.");
            List<String> dates=new ArrayList<>();
            for(String h:cols.keySet())if(!h.equals("CONS_NO")&&!h.equals("FLAG"))dates.add(h);
            if(dates.isEmpty())throw new IOException("No consumption columns available.");
            dates.sort(DataCleaner::compareDateHeaders);
            csv.printRecord("CONS_NO","FLAG","MEAN","STD_DEV","MIN","MAX","CV","PAR","TOTAL","TREND","LAG7_AUTOCORR","CONSUMPTION_DEVIATION");
            for(CSVRecord r:parser){
                double[] v=new double[dates.size()];
                for(int i=0;i<v.length;i++){
                    try{v[i]=Double.parseDouble(r.get(dates.get(i)));}
                    catch(NumberFormatException ex){throw new IOException("Unresolved reading for " + r.get("CONS_NO"),ex);}
                    if(!Double.isFinite(v[i]))throw new IOException("Invalid cleaned reading for " + r.get("CONS_NO"));
                }
                int n=v.length;double sum=0,min=Double.POSITIVE_INFINITY,max=0;
                for(double x:v){sum+=x;min=Math.min(min,x);max=Math.max(max,x);}
                double mean=sum/n,var=0;
                for(double x:v)var+=(x-mean)*(x-mean);
                double std=Math.sqrt(var/n),cv=mean!=0?std/mean:0,par=mean!=0?max/mean:0;
                double slopeNumerator=0,center=(n-1)/2.0,slopeDenominator=n*(n*n-1.0)/12.0;
                for(int i=0;i<n;i++)slopeNumerator+=(i-center)*(v[i]-mean);
                double trend=slopeDenominator!=0?slopeNumerator/slopeDenominator:0;
                double lag=0;
                if(n>7){double mx=0,my=0;int pairs=n-7;
                    for(int i=7;i<n;i++){mx+=v[i];my+=v[i-7];}mx/=pairs;my/=pairs;
                    double num=0,dx=0,dy=0;
                    for(int i=7;i<n;i++){double x=v[i]-mx,y=v[i-7]-my;num+=x*y;dx+=x*x;dy+=y*y;}
                    lag=dx>0&&dy>0?num/Math.sqrt(dx*dy):0;
                }
                double baseline=0;int prior=Math.min(7,n);
                for(int i=0;i<prior;i++)baseline+=v[i];baseline/=prior;
                double deviation=baseline!=0?(mean-baseline)/baseline:0;
                csv.printRecord(r.get("CONS_NO"),r.get("FLAG"),mean,std,min,max,cv,par,sum,trend,lag,deviation);
            }
        }
        return output;
    }
}
