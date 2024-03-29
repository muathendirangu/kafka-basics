import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Client;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.Hosts;
import com.twitter.hbc.core.HttpHosts;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TwitterProducer {

    Logger logger = LoggerFactory.getLogger(TwitterProducer.class.getName());

    String consumerKey = "yIFEi63Ka4GOqLFNmEgy345XJ";
    String consumerSecret = "4XYvue3XU66EBCNU3riRH27XDytZoMA1h2sib4ViZwR0gBzrsD";
    String token = "2819119731-3QfcbeNUyyvrrNVNOpDVUSiVFlLMtx6JHS1Hw69";
    String secret = "f7ixBJW9cTW7bOFksQBlsxc1XbuOlPtFDTm9XQ7236ozF";

    // Optional: set up some followings and track terms
    List<String> terms = Lists.newArrayList("golang","react","javascript");

    public TwitterProducer(){}
    public static void main(String[] args) {
        new TwitterProducer().run();
    }

    public void  run(){
        logger.info("setup the application");

        /** Set up your blocking queues: Be sure to size these properly based on expected TPS of your stream */
        BlockingQueue<String> msgQueue = new LinkedBlockingQueue<String>(1000);

        //create a twitter client
        Client client = createTwitterClient(msgQueue);

        client.connect();

        //create a kafka producer
        KafkaProducer<String, String> producer = createKafkaProducer();


        //loop to send tweets to kafka
        // on a different thread, or multiple different threads....
        while (!client.isDone()) {
            String msg = null;
            try {
                msg = msgQueue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
                client.stop();
            }
            if (msg != null){
                logger.info(msg);
                producer.send(new ProducerRecord<String, String>("twitter_topic", null, msg), new Callback() {
                    public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                        if (e != null){
                            logger.error("an error occurred", e);
                        }
                    }
                });
            }
        }
        logger.info("End of application execution");

    }

    public Client createTwitterClient(BlockingQueue<String> msgQueue){

        /** Declare the host you want to connect to, the endpoint, and authentication (basic auth or oauth) */
        Hosts hosebirdHosts = new HttpHosts(Constants.STREAM_HOST);
        StatusesFilterEndpoint hosebirdEndpoint = new StatusesFilterEndpoint();

        hosebirdEndpoint.trackTerms(terms);

        // These secrets should be read from a config file
        Authentication hosebirdAuth = new OAuth1(consumerKey, consumerSecret, token, secret);

        ClientBuilder builder;
        builder = new ClientBuilder()
                .name("kafka-client") // optional: mainly for the logs
                .hosts(hosebirdHosts)
                .authentication(hosebirdAuth)
                .endpoint(hosebirdEndpoint)
                .processor(new StringDelimitedProcessor(msgQueue));

        Client hosebirdClient = builder.build();
        return hosebirdClient;
        }

        public KafkaProducer<String, String> createKafkaProducer(){
            String bootstrapServers = "127.0.0.1:9092";



            //create producer properties
            Properties properties = new Properties();
            properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            //add safety properties to producer
            properties.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
            properties.setProperty(ProducerConfig.RETRIES_CONFIG, Integer.toString(Integer.MAX_VALUE));
            properties.setProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5"); // if kafka > 2.1 keep 5 otherwise 1

            //high throughput at the expense of latency and cpu usage
            properties.setProperty(ProducerConfig.LINGER_MS_CONFIG,"20");
            properties.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
            properties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32*1024));//32 KB Batch size

            //create the producer
            KafkaProducer<String, String> producer = new KafkaProducer<String, String>(properties);

            return producer;
        }
}
