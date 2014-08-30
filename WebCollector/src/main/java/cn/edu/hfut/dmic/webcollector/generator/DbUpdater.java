/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cn.edu.hfut.dmic.webcollector.generator;

import cn.edu.hfut.dmic.webcollector.model.AvroModel;
import cn.edu.hfut.dmic.webcollector.model.CrawlDatum;
import cn.edu.hfut.dmic.webcollector.model.Link;
import cn.edu.hfut.dmic.webcollector.model.Page;
import cn.edu.hfut.dmic.webcollector.parser.ParseData;
import cn.edu.hfut.dmic.webcollector.util.Config;
import cn.edu.hfut.dmic.webcollector.util.FileUtils;
import cn.edu.hfut.dmic.webcollector.util.Log;
import cn.edu.hfut.dmic.webcollector.util.Task;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.reflect.ReflectDatumReader;
import org.apache.avro.reflect.ReflectDatumWriter;

/**
 *
 * @author hu
 */
public class DbUpdater extends Task {

    String crawl_path;

    public DbUpdater(String crawl_path) {
        this.crawl_path = crawl_path;
    }

    public static void backup(String backuppath) throws IOException {
        File oldfile = new File(backuppath, Config.old_info_path);
        File currentfile = new File(backuppath, Config.current_info_path);
        FileUtils.copy(currentfile, oldfile);
    }

    public boolean isLocked() throws IOException {
        File lockfile = new File(crawl_path + "/" + Config.lock_path);
        if (!lockfile.exists()) {
            return false;
        }
        String lock = new String(FileUtils.readFile(lockfile), "utf-8");
        return lock.equals("1");
    }

    public void lock() throws IOException {
        FileUtils.writeFile(crawl_path + "/" + Config.lock_path, "1".getBytes("utf-8"));
    }

    public void unlock() throws IOException {
        FileUtils.writeFile(crawl_path + "/" + Config.lock_path, "0".getBytes("utf-8"));
    }
    // DataFileWriter<CrawlDatum> dataFileWriter;
    DbWriter<CrawlDatum> updater_writer;
    int updaterCount;

    public void updateAll(ArrayList<CrawlDatum> datums) throws IOException {
        File currentfile = new File(crawl_path, Config.current_info_path);
        if (!currentfile.getParentFile().exists()) {
            currentfile.getParentFile().mkdirs();
        }
        DbWriter<CrawlDatum> writer = new DbWriter<CrawlDatum>(CrawlDatum.class, currentfile);
        for (CrawlDatum crawldatum : datums) {
            writer.write(crawldatum);
        }
        writer.close();
    }

    public void initUpdater() throws UnsupportedEncodingException, IOException {
        File currentfile = new File(crawl_path, Config.current_info_path);
        if (!currentfile.getParentFile().exists()) {
            currentfile.getParentFile().mkdirs();
        }
        if (currentfile.exists()) {
            updater_writer = new DbWriter<CrawlDatum>(CrawlDatum.class, currentfile, true);
        } else {
            updater_writer = new DbWriter<CrawlDatum>(CrawlDatum.class, currentfile, false);
        }
        updaterCount = 0;
    }

    /*
     public synchronized void append(CrawlDatum crawldatum) throws IOException {
     updater_writer.write(crawldatum);
     if (updaterCount % 200 == 0) {
     updater_writer.flush();
     }
     updaterCount++;
     }
     */
    public void closeUpdater() throws IOException {
        updater_writer.close();
    }

    public void merge(String segment_path) throws IOException {

        Log.Infos("merge",taskname,segment_path);
        File file_fetch = new File(segment_path, "fetch");
        if (!file_fetch.exists()) {
            return;
        }

        File file_current = new File(crawl_path, Config.current_info_path);
        DbReader<CrawlDatum> reader_current = new DbReader<CrawlDatum>(CrawlDatum.class, file_current);
        DbReader<CrawlDatum> reader_fetch = new DbReader<CrawlDatum>(CrawlDatum.class, file_fetch);

        HashMap<String, Integer> indexmap = new HashMap<String, Integer>();

        ArrayList<CrawlDatum> datums_origin = new ArrayList<CrawlDatum>();
        CrawlDatum datum = null;
        while (reader_current.hasNext()) {
            datum = reader_current.readNext();
            datums_origin.add(datum);
            indexmap.put(datum.url, datums_origin.size() - 1);
        }

        while (reader_fetch.hasNext()) {
            datum = reader_fetch.readNext();
            if (indexmap.containsKey(datum.url)) {
                if (datum.status == Page.UNFETCHED) {
                    continue;
                } else {
                    int preindex = indexmap.get(datum.url);
                    datums_origin.set(preindex, datum);
                    indexmap.put(datum.url, preindex);
                }

            } else {
                datums_origin.add(datum);
                indexmap.put(datum.url, datums_origin.size() - 1);
            }

        }
        reader_fetch.close();

        File file_parse = new File(segment_path, "parse_data");
        if (file_parse.exists()) {
            DbReader<ParseData> reader_parse = new DbReader<ParseData>(ParseData.class, file_parse);
            ParseData parseresult = null;
            while (reader_parse.hasNext()) {
                parseresult = reader_parse.readNext();
                for (Link link : parseresult.links) {
                    datum = new CrawlDatum();
                    datum.url = link.url;
                    datum.status = Page.UNFETCHED;
                    if (indexmap.containsKey(datum.url)) {
                        continue;
                    } else {
                        datums_origin.add(datum);
                        indexmap.put(datum.url, datums_origin.size() - 1);
                    }
                }
            }
            reader_parse.close();
        }

        reader_current.close();

        updateAll(datums_origin);

    }

    /*
     public void merge() throws IOException{
        
     File currentfile=new File(crawl_path, Config.current_info_path);
     DbReader<CrawlDatum> reader=new DbReader<CrawlDatum>(CrawlDatum.class,currentfile);

     HashMap<String,Integer> indexmap=new HashMap<String, Integer>();
     ArrayList<CrawlDatum> origin_datums=new ArrayList<CrawlDatum>();
     CrawlDatum crawldatum=null;
     while(reader.hasNext()){
     crawldatum=reader.readNext();
     String url=crawldatum.url;
     if(indexmap.containsKey(crawldatum.url)){
     int preindex=indexmap.get(url);
     CrawlDatum pre_datum=origin_datums.get(preindex);
     if(crawldatum.status==Page.UNFETCHED){                    
     continue;
     }else if(pre_datum.fetchtime>=crawldatum.fetchtime){
     continue;
     }else{
     origin_datums.set(preindex, crawldatum);
     indexmap.put(url, preindex);
     }
                
     }else{
     origin_datums.add(crawldatum);
     indexmap.put(url, origin_datums.size()-1);
     }
            
     }
       
     reader.close();
     updateAll(origin_datums);

     }
     */
}
