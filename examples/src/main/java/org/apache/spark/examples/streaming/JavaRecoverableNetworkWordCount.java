/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.examples.streaming;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.io.FileWriteMode;
import scala.Tuple2;

import com.google.common.io.Files;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.util.LongAccumulator;

/**
 * Use this singleton to get or register a Broadcast variable.
 */
class JavaWordExcludeList {

  private static volatile Broadcast<List<String>> instance = null;

  public static Broadcast<List<String>> getInstance(JavaSparkContext jsc) {
    if (instance == null) {
      synchronized (JavaWordExcludeList.class) {
        if (instance == null) {
          List<String> wordExcludeList = Arrays.asList("a", "b", "c");
          instance = jsc.broadcast(wordExcludeList);
        }
      }
    }
    return instance;
  }
}

/**
 * Use this singleton to get or register an Accumulator.
 */
class JavaDroppedWordsCounter {

  private static volatile LongAccumulator instance = null;

  public static LongAccumulator getInstance(JavaSparkContext jsc) {
    if (instance == null) {
      synchronized (JavaDroppedWordsCounter.class) {
        if (instance == null) {
          instance = jsc.sc().longAccumulator("DroppedWordsCounter");
        }
      }
    }
    return instance;
  }
}

/**
 * Counts words in text encoded with UTF8 received from the network every second. This example also
 * shows how to use lazily instantiated singleton instances for Accumulator and Broadcast so that
 * they can be registered on driver failures.
 *
 * Usage: JavaRecoverableNetworkWordCount <hostname> <port> <checkpoint-directory> <output-file>
 *   <hostname> and <port> describe the TCP server that Spark Streaming would connect to receive
 *   data. <checkpoint-directory> directory to HDFS-compatible file system which checkpoint data
 *   <output-file> file to which the word counts will be appended
 *
 * <checkpoint-directory> and <output-file> must be absolute paths
 *
 * To run this on your local machine, you need to first run a Netcat server
 *
 *      `$ nc -lk 9999`
 *
 * and run the example as
 *
 *      `$ ./bin/run-example org.apache.spark.examples.streaming.JavaRecoverableNetworkWordCount \
 *              localhost 9999 ~/checkpoint/ ~/out`
 *
 * If the directory ~/checkpoint/ does not exist (e.g. running for the first time), it will create
 * a new StreamingContext (will print "Creating new context" to the console). Otherwise, if
 * checkpoint data exists in ~/checkpoint/, then it will create StreamingContext from
 * the checkpoint data.
 *
 * Refer to the online documentation for more details.
 */
public final class JavaRecoverableNetworkWordCount {
  private static final Pattern SPACE = Pattern.compile(" ");

  private static JavaStreamingContext createContext(String ip,
                                                    int port,
                                                    String checkpointDirectory,
                                                    String outputPath) {

    // If you do not see this printed, that means the StreamingContext has been loaded
    // from the new checkpoint
    System.out.println("Creating new context");
    File outputFile = new File(outputPath);
    if (outputFile.exists()) {
      outputFile.delete();
    }
    SparkConf sparkConf = new SparkConf().setAppName("JavaRecoverableNetworkWordCount");
    // Create the context with a 1 second batch size
    JavaStreamingContext ssc = new JavaStreamingContext(sparkConf, Durations.seconds(1));
    ssc.checkpoint(checkpointDirectory);

    // Create a socket stream on target ip:port and count the
    // words in input stream of \n delimited text (e.g. generated by 'nc')
    JavaReceiverInputDStream<String> lines = ssc.socketTextStream(ip, port);
    JavaDStream<String> words = lines.flatMap(x -> Arrays.asList(SPACE.split(x)).iterator());
    JavaPairDStream<String, Integer> wordCounts = words.mapToPair(s -> new Tuple2<>(s, 1))
        .reduceByKey((i1, i2) -> i1 + i2);

    wordCounts.foreachRDD((rdd, time) -> {
      // Get or register the excludeList Broadcast
      Broadcast<List<String>> excludeList =
          JavaWordExcludeList.getInstance(new JavaSparkContext(rdd.context()));
      // Get or register the droppedWordsCounter Accumulator
      LongAccumulator droppedWordsCounter =
          JavaDroppedWordsCounter.getInstance(new JavaSparkContext(rdd.context()));
      // Use excludeList to drop words and use droppedWordsCounter to count them
      String counts = rdd.filter(wordCount -> {
        if (excludeList.value().contains(wordCount._1())) {
          droppedWordsCounter.add(wordCount._2());
          return false;
        } else {
          return true;
        }
      }).collect().toString();
      String output = "Counts at time " + time + " " + counts;
      System.out.println(output);
      System.out.println("Dropped " + droppedWordsCounter.value() + " word(s) totally");
      System.out.println("Appending to " + outputFile.getAbsolutePath());
      Files.asCharSink(outputFile, StandardCharsets.UTF_8, FileWriteMode.APPEND)
        .write(output + "\n");
    });

    return ssc;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.err.println("You arguments were " + Arrays.asList(args));
      System.err.println(
          "Usage: JavaRecoverableNetworkWordCount <hostname> <port> <checkpoint-directory>\n" +
          "     <output-file>. <hostname> and <port> describe the TCP server that Spark\n" +
          "     Streaming would connect to receive data. <checkpoint-directory> directory to\n" +
          "     HDFS-compatible file system which checkpoint data <output-file> file to which\n" +
          "     the word counts will be appended\n" +
          "\n" +
          "In local mode, <master> should be 'local[n]' with n > 1\n" +
          "Both <checkpoint-directory> and <output-file> must be absolute paths");
      System.exit(1);
    }

    String ip = args[0];
    int port = Integer.parseInt(args[1]);
    String checkpointDirectory = args[2];
    String outputPath = args[3];

    // Function to create JavaStreamingContext without any output operations
    // (used to detect the new context)
    Function0<JavaStreamingContext> createContextFunc =
        () -> createContext(ip, port, checkpointDirectory, outputPath);

    JavaStreamingContext ssc =
      JavaStreamingContext.getOrCreate(checkpointDirectory, createContextFunc);
    ssc.start();
    ssc.awaitTermination();
  }
}
