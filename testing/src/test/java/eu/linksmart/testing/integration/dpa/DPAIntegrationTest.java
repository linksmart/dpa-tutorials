package eu.linksmart.testing.integration.dpa;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.io.CharStreams;
import eu.linksmart.api.event.types.EventEnvelope;
import eu.linksmart.api.event.types.Statement;
import eu.linksmart.api.event.types.impl.MultiResourceResponses;
import eu.linksmart.services.payloads.SenML.SenML;
import eu.linksmart.services.payloads.ogc.sensorthing.OGCEventBuilder;
import eu.linksmart.services.payloads.ogc.sensorthing.Observation;
import eu.linksmart.services.payloads.ogc.sensorthing.linked.ObservationImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Content;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.entity.ContentType;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Created by José Ángel Carvajal on 14.06.2018 a researcher of Fraunhofer FIT.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DPAIntegrationTest implements MqttCallback{
    private ObjectMapper mapper ;
    private MqttClient sender, subscriber;
    private String agentURL;
    private static final int totalEvents = 10, initialStatements=11;
    private final List<Boolean> messages= new ArrayList<>();
    private int messageN=0, message1N=0, message2N=0;
    private double average = 0.0;
    private boolean lastOK= true, receivingFail=false;
    private String counting = "counting", count1 = "count1", count2 = "count2";
    private final boolean[] arrived= {false};

    @Before
    public void initialization(){
        String url = System.getenv().getOrDefault("INPUT_BROKER_URL", "tcp://localhost:1883");
        String url2 = System.getenv().getOrDefault("OUTPUT_BROKER_URL", "tcp://localhost:1883");

        agentURL = System.getenv().getOrDefault("AGENT_URL", "http://localhost:8319/");
        if (agentURL.trim().charAt(agentURL.length() - 1) != '/')
            agentURL = agentURL + "/";
       waitingAgent();
        try {
            mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

            sender = new MqttClient(url, "sender"+UUID.randomUUID().toString(),new MemoryPersistence());
            subscriber = new MqttClient(url2, "subscriber"+UUID.randomUUID().toString(),new MemoryPersistence());
            subscriber.setCallback(this);

            connect(sender);
            connect(subscriber);

        }catch (Exception e){
            e.printStackTrace();
            fail(e.getMessage());
        }

    }

    private void waitingAgent() {
        boolean loaded =false;
        int i=0;
        do {
            try {

                if(Request.Get(testURL(agentURL)).execute().returnResponse().getStatusLine().getStatusCode()==200) {
                    loaded = true;
                    Thread.sleep(5000);
                }else {
                    fail("Agent did not start!");
                    System.exit(-1);
                }
            }catch (Exception ignored){
                try {
                    i++;
                    System.err.println("Waiting agent...");
                    Thread.sleep(1000);
                }catch (Exception ig){
                    // nothing
                }
                if(i>60*3){
                    fail("Agent did not start!");
                    System.exit(-1);
                }

            }
        }while (!loaded );
    }


    @Test
    public void T00_hello() {
        upkeep();

        Response response = execute(Request.Get(testURL(agentURL)));
        HttpResponse httpResponse = collectHttpResponse(response);
        String content= toString(httpResponse.getEntity());

        assertEquals("Must be 200 (OK)", 200, httpResponse.getStatusLine().getStatusCode());

        Map<String, Object> body = parse(content,Map.class);

        assertEquals("Body should be a not empty map", true, !body.isEmpty());

        MultiResourceResponses<Statement> root = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"))),200);

        ending();
    }
    @Test
    public void T1_counting() {
        upkeep();

        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+counting+"/")).bodyString(
                        "{" +
                                "\"name\":\"countTotal\"," +
                                "\"statement\":\"select count(*) from Observation(datastream.id.toString() = '1' or datastream.id.toString() = '2') \"" +
                                "}",
                        ContentType.APPLICATION_JSON
        )),201);
        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+count1+"/")).bodyString(
                        "{" +
                                "\"name\":\"count1\"," +
                                "\"statement\":\"select count(*) from Observation(datastream.id.toString() = '1')\"" +
                                "}",
                        ContentType.APPLICATION_JSON
                )),201);

        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+count2+"/")).bodyString(
                        "{" +
                                "\"name\":\"count2\"," +
                                "\"statement\":\"select count(*) from Observation(datastream.id.toString() = '2')\"" +
                                "}",
                        ContentType.APPLICATION_JSON
                )),201);

        //subscribe("LS/#");
        for ( int i=0; i<totalEvents;i++) {
            /*while (!subscriber.isConnected()){
                connect(subscriber);
                subscribe("LS/#");
                wait(1000);
            }
            messages.add(false);*/
            EventEnvelope envelope = createEvent(String.valueOf(Math.floorMod(i, 2) + 1), String.valueOf(Math.floorMod(i, 2) + 1), i);
            publish(envelope.getClassTopic() + envelope.getAttributeId(), envelope);

            wait(100);

        }
        MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+counting+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});
        MultiResourceResponses<ObservationImpl> result1 = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+count1+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});
        MultiResourceResponses<ObservationImpl> result2 = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+count2+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});


        execute(Request.Delete(testURL(agentURL+"/statement/"+counting+"/")));
        execute(Request.Delete(testURL(agentURL+"/statement/"+count1+"/")));
        execute(Request.Delete(testURL(agentURL+"/statement/"+count2+"/")));


        wait(1000);

       //unsubscribe("LS/#");
        assertEquals("total message must be equal to message type 1 and 2", result.getHeadResource().getIntResult(),result1.getHeadResource().getIntResult()+result2.getHeadResource().getIntResult());

       // if(messages.stream().anyMatch(p->!p))
       //     fail("Not all messages arrived after finishing the test!");


        ending();
    }
    /*
    "{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select avg(intResult) from Observation(datastream.id.toString() like 'tmp%').win:time(30 sec)\"\n" +
                                "}"
    * */
    @Test
    public void Tutorial1_aggregate() {
        upkeep();
        final String tutorial_short_name="average_temperature";

        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("aggregation")),
                        ContentType.APPLICATION_JSON
                )),201);



        wait(1000);
        MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));


        assertEquals("expect between 3 to 5 events",25.0,result.getHeadResource().getDoubleResult(),15.0);

        ending();
    }
    /*
    * "{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select datastream.thing.id from Observation(datastream.id.toString() like 'fill%' and intResult > 0)\"\n" +
                                "}"
    * */
    @Test
    public void Tutorial2_alert() {
        upkeep();
        final String tutorial_short_name="full_alert";

        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("query")),
                        ContentType.APPLICATION_JSON
                )),201);



        wait(1500);
        MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        // weak test
        assertEquals("There is a last output in the statement",false,result.getResources().isEmpty());

        ending();
    }
    /*
    * "{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select datastream.thing.id as binID, intResult*10000 as weight  from Observation(datastream.id.toString() like 'fill%')\"\n" +
                                "}"
    * */
    @Test
    public void Tutorial3_postProcess() {
        upkeep();
        final String tutorial_short_name="weight";
        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("post-process")),
                        ContentType.APPLICATION_JSON
                )),201);



        wait(3000);
        MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        assertEquals("is value be over 1000?",true,(int)((Map)result.getHeadResource().getResult()).get("weight")>1000);

        ending();
    }
    /*
    *"{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select 'bin1' as binID  from Observation(datastream.id.toString() like '%1'  and ( intResult > 10 or intResult > 0)).win:time(1 sec)  having count(*)=2\"\n" +
                                "}" */
    @Test
    public void Tutorial4_fusion() {
        upkeep();
        final String tutorial_short_name="stinky_bin";
        processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("fusion")),
                        ContentType.APPLICATION_JSON
                )),201);



        wait(1500);
        MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        assertEquals("Bin1 stinks?",true,result.getHeadResource().getResult() instanceof String && result.getHeadResource().getResult().toString().contains("bin1"));

        ending();
    }
/*
"{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select 'bin2' as binID  from Observation(datastream.id.toString() like '%2'  and ( intResult > 10 or intResult > 0)).win:time(1 sec)  having count(*)=2\",\n" +
                                "    \"scope\":[\"city\"]\n" +
                                "}"
* */
    @Test
    public void Tutorial5_route_broker() {
        upkeep();
        final String tutorial_short_name="stinky_bin_route";
        MultiResourceResponses responses;

        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("broker_route_dummy_to_update"))
                        ,
                        ContentType.APPLICATION_JSON
                )),201);


        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("broker_route"))
                        ,
                        ContentType.APPLICATION_JSON
                )),200);


        arrived[0] =false;
        final String[] arrTopic ={""};
        MqttClient city =prepareSecondBroker();

        city.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if((topic.contains("DPA")||topic.contains("LA"))&&topic.contains("Datastreams")) {
                    arrived[0] = true;
                    arrTopic[0] = topic;
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        tryTill(10000,city,"#");
        unsubscribe(city, "#");
        //MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        assertEquals("Messaged arrive to second (city) broker?",true,arrived[0]);
        assertEquals("The arrived topic and topic defined of the query do not match",arrTopic[0],(responses.getResponsesTail().getTopics().get(0)));

        ending();
    }
    /*
    *
"{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select 'bin3' as binID  from Observation(datastream.id.toString() like '%3'  and ( intResult> 10 or intResult> 0)).win:time(1 sec)  having count(*)=2\",\n" +
                                "    \"scope\":[\"city\"],\n" +
                                "    \"output\":[\"LS/my/topic\"]\n" +
                                "}",
    * */
    @Test
    public void Tutorial6_route_topic_broker() {
        upkeep();
        final String tutorial_short_name="stinky_bin_route2";
        MultiResourceResponses responses;


        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("topic_broker_route_dummy_to_update"))
                        ,
                        ContentType.APPLICATION_JSON
                )),201);


        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("topic_broker_route")),
                        ContentType.APPLICATION_JSON
                )),200);


        arrived[0] =false;

        MqttClient city =prepareSecondBroker();

        final String[] arrTopic ={""};

        city.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if(((topic.contains("DPA")||topic.contains("LA"))&&topic.contains("Datastreams"))|| topic.contains("LS/my/topic")) {
                    arrived[0] = true;
                    arrTopic[0] = topic;
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });
        tryTill(10000,city,"#");
        unsubscribe(city, "#");
        //MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        assertEquals("Messaged arrive to second (city) broker?",true,arrived[0]);
        // TODO: if LS-369 is fixed uncomment this two lines
        assertEquals("The arrived topic and topic defined of the query do not match",arrTopic[0],(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The topic defined in the query and response topic  do not match","LS/my/topic",(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The defined topic and the arrived topic of the query do not match","LS/my/topic",arrTopic[0]);


        ending();
    }
    /*
    "{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select 'bin4' as binID  from Observation(datastream.id.toString() like '%4'  and ( intResult> 10 or intResult > 0)).win:time(1 sec)  having count(*)=2\",\n" +
                                "    \"scope\":[\"city\"],\n" +
                                "    \"output\":[\"LS/DPA/1/SenML/10/Event/stinky_bin_route_translate\"],\n" +
                                "    \"resultType\":\"SenML\"\n" +
                                "}"
    * */
    @Test
    public void Tutorial7_route_topic_broker_translate() {
        upkeep();
        final String tutorial_short_name="stinky_bin_route_translate";
        MultiResourceResponses responses;

        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("payload_translate_dummy_to_update"))
                        ,
                        ContentType.APPLICATION_JSON
                )),201);

        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("payload_translate")),
                        ContentType.APPLICATION_JSON
                )),200);


        arrived[0] =false;
        final String[] arrTopic ={""};

        MqttClient city =prepareSecondBroker();

        city.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if(((topic.contains("DPA")||topic.contains("LA"))&&topic.contains("SenML"))|| topic.contains("LS/DPA/1/SenML/10/Event/stinky_bin_route_translate")) {
                    parse(message.getPayload(), SenML.class);
                    arrived[0]=true;
                    arrTopic[0]=topic;
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        tryTill(10000,city,"#");
        unsubscribe(city, "#");
        //MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        assertEquals("Messaged arrive to second (city) broker?",true,arrived[0]);
        // TODO: if LS-369 is fixed uncomment this two lines
        assertEquals("The arrived topic and topic defined of the query do not match",arrTopic[0],(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The topic defined in the query and response topic  do not match","LS/DPA/1/SenML/10/Event/stinky_bin_route_translate",(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The defined topic and the arrived topic of the query do not match","LS/DPA/1/SenML/10/Event/stinky_bin_route_translate",arrTopic[0]);

        ending();
    }
    /*

    "{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select 'bin5' as binID  from Observation(datastream.id.toString() like '%5'  and ( intResult > 10 or intResult > 0)).win:time(1 sec)  having count(*)=2\",\n" +
                                "    \"scope\":[\"city\"],\n" +
                                "    \"output\":[\"LS/DPA/1/RAW/0/RAW/stinky_bin\"],\n" +
                                "    \"resultType\":\"none\"\n" +
                                "}"
    * */
    @Test
    public void Tutorial8_route_topic_broker_transform() {
        upkeep();
        final String tutorial_short_name="stinky_bin_route_transform";
        MultiResourceResponses responses;

        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("payload_transform_dummy_to_update"))
                        ,
                        ContentType.APPLICATION_JSON
                )),201);



        responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("payload_transform")),
                        ContentType.APPLICATION_JSON
                )),200);


        arrived[0] =false;
        final String[] arrTopic ={""};
        final String[] error ={""};


        MqttClient city =prepareSecondBroker();

        city.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if(((topic.contains("DPA")||topic.contains("LA"))&&(topic.contains("Datastreams")||topic.toLowerCase().contains("RAW")||topic.contains("SenML")))|| topic.contains("LS/DPA/1/RAW/0/RAW/stinky_bin")) {
                    Map event =parse(message.getPayload(), Map.class);
                    if(!event.containsKey("binID"))
                        error[0]= ("Event doesn't contain bin id!");
                    arrived[0]=true;
                    arrTopic[0]=topic;
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        tryTill(10000,city,"#");
        unsubscribe(city, "#");
        //MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        if(!"".equals(error[0]))
            fail(error[0]);

        assertEquals("Messaged arrive to second (city) broker?",true,arrived[0]);
        // TODO: if LS-369 is fixed uncomment this two lines
        assertEquals("The arrived topic and topic defined of the query do not match",arrTopic[0],(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The topic defined in the query and response topic  do not match","LS/DPA/1/RAW/0/RAW/stinky_bin",(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The defined topic and the arrived topic of the query do not match","LS/DPA/1/RAW/0/RAW/stinky_bin",arrTopic[0]);

        ending();
    }
/*
"{\n" +
                                "    \"name\": \""+tutorial_short_name+"\" ,\n" +
                                "    \"statement\": \"select event from Observation as event\",\n" +
                                "    \"LSApiKeyName\": \"post2pub\",\n" +
                                "    \"scope\":[\"appbackend\"],\n" +
                                "    \"output\":[\"routed\"],\n" +
                                "    \"publisher\":\"REST_POST\"\n" +
                                "}"
* */
    @Test
    public void Tutorial9_protocol_translate() {
        upkeep();
        final String tutorial_short_name="protocol_translate";
        System.out.println("PUT "+agentURL+"/statement/"+tutorial_short_name+"/");
        System.out.println("Header: "+ContentType.APPLICATION_JSON);
        System.out.println("Body: "+agentURL+"/statement/"+tutorial_short_name+"/");
        MultiResourceResponses responses = processResponse(execute(
                Request.Put(testURL(agentURL+"/statement/"+tutorial_short_name+"/")).bodyString(
                        toString(getStatement("protocol_translate")),
                        ContentType.APPLICATION_JSON
                )),201);


        arrived[0] =false;
        final String[] arrTopic ={""};
        final String[] error ={""};
        System.out.println("CONN "+subscriber.getCurrentServerURI());
        connect(subscriber);
        System.out.println("SUB "+subscriber.getCurrentServerURI()+"#");
        subscribe(subscriber,"#");

        subscriber.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                if(topic.contains("routed")) {
                    arrived[0]=true;
                    arrTopic[0]=topic;
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        tryTill(10000,subscriber,"#");
        unsubscribe(subscriber, "#");
        //MultiResourceResponses<ObservationImpl> result = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"+tutorial_short_name+"/output/"))),200,new TypeReference<MultiResourceResponses<ObservationImpl>>() {});

        System.out.println("Executing: DELETE "+agentURL+"/statement/"+tutorial_short_name+"/");
        execute(Request.Delete(testURL(agentURL+"/statement/"+tutorial_short_name+"/")));

        if(!"".equals(error[0]))
            fail(error[0]);

        assertEquals("Messaged arrive to the endpoint?",true,arrived[0]);
        // TODO: if LS-369 is fixed uncomment this two lines
        assertEquals("The arrived topic and topic defined of the query do not match",arrTopic[0],(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The topic defined in the query and response topic  do not match","routed",(responses.getResponsesTail().getTopics().get(0)));
        assertEquals("The defined topic and the arrived topic of the query do not match","routed",arrTopic[0]);

        ending();
    }
    private Map getStatement(String name){
        Map statement = null;
        String path = "./tutorial.json";
        File source = new File(path);
        if(!source.exists())
            fail("statement source file not found!");
        try {
            statement =parse( Files.readAllBytes(Paths.get(path)), Map.class);
            return (Map) statement.get(name);
        } catch (Exception e) {
            fail(e.getMessage());

        }
        return statement;
    }
    private void tryTill(long time, MqttClient client,String... topics){
        long before =((new Date()).getTime()), after;
        do{
            System.out.println("Trying ... ");
            System.out.println("CONN "+subscriber.getCurrentServerURI());
            connect(client);
            System.out.println("SUB "+subscriber.getCurrentServerURI()+"{"+ Arrays.stream(topics).collect(Collectors.joining(" , "))+"}");
            subscribe(client,topics);
            after =((new Date()).getTime());
            wait(100);
        }while (after-before<time&&!arrived[0]);

    }
    private MqttClient prepareSecondBroker(){
        try {
            String url2 = System.getenv().getOrDefault("BROKER2_URL", System.getenv().getOrDefault("CITY_URL", "tcp://localhost:1881"));
            return new MqttClient(url2, "city"+UUID.randomUUID().toString(),new MemoryPersistence());
        }catch (Exception e){
            fail(e.getMessage());
            System.exit(-1);
            return null;
        }
    }
    private void upkeep(){
        isLastOK();

        MultiResourceResponses<Statement> root = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"))),200);

        assertEquals("There should be only the initial statements",initialStatements,root.getResources().size());
    }

    private void ending(){

        MultiResourceResponses<Statement> root = processResponse(execute(Request.Get(testURL(agentURL+"/statement/"))),200);

        assertEquals("There should be only the initial statements",initialStatements,root.getResources().size());

        lastOK =true;
    }
    private String toString(HttpEntity entity){

        try (final Reader reader = new InputStreamReader(entity.getContent())) {
            return CharStreams.toString(reader);
        } catch (Exception e) {
            fail(e.getMessage());
        }
        return null;
    }
    private String toString(Object object){
        try {
            return  mapper.writeValueAsString(object);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }
    private void isLastOK(){
        if(lastOK)
            lastOK = false;
        else
            fail("Last test failed!");
    }
    private void wait(int i){

        try {
            Thread.sleep(i);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private EventEnvelope createEvent(String thingId, String streamId,Object value){

        try {
            return (new OGCEventBuilder()).factory(thingId,streamId,value,(new Date()).getTime(),null,new Hashtable<>());
        }catch (Exception e){
            fail(e.getMessage());
        }
        return null;
    }
    private void publish(String topic,Object payload){
        connect(sender);
        try {
            sender.publish(topic,serialize(payload),0,false);
        }catch (Exception e){
            fail(e.getMessage());
        }
    }

    private <T> MultiResourceResponses<T> processResponse(Response response, int expectedResponseCode, TypeReference reference){


        MultiResourceResponses<T> responses = parse(_processResponse(response,expectedResponseCode),reference);
        //   assertEquals("The header response code and the body response code  must be equal", responses.getOverallStatus(), httpResponse.getStatusLine().getStatusCode());

        return responses;
    }
    private <T> MultiResourceResponses<T> processResponse(Response response, int expectedResponseCode){


        MultiResourceResponses<T> responses = parse(_processResponse(response,expectedResponseCode),MultiResourceResponses.class);

        //   assertEquals("The header response code and the body response code  must be equal", responses.getOverallStatus(), httpResponse.getStatusLine().getStatusCode());

        return responses;
    }
    private String _processResponse(Response response, int expectedResponseCode){
        HttpResponse httpResponse = null;
        try {
            httpResponse = response.returnResponse();
        } catch (IOException e) {
            fail(e.getMessage());
        }
        String content= toString(httpResponse.getEntity());
        if(expectedResponseCode!= httpResponse.getStatusLine().getStatusCode()){
            try {
                MultiResourceResponses responses = mapper.readValue(content,MultiResourceResponses.class);
                fail("Expected code "+expectedResponseCode+" received "+httpResponse.getStatusLine().getStatusCode()+" server response message"+responses.getResponsesTail().getMessage());
            }catch (Exception ignored){
                assertEquals("Must be "+expectedResponseCode+" ", expectedResponseCode, httpResponse.getStatusLine().getStatusCode());
            }
        }


       return content;
    }
    private void subscribe(String... topics) {
        subscribe(subscriber,topics);

    }
    private void subscribe(MqttClient subscriber,String... topics) {
        connect(subscriber);
        try {
            int[] qos= new int[topics.length];

            for (int i=0;i<topics.length;i++)
                qos[i]=2;

            System.out.println("SUB "+subscriber.getCurrentServerURI()+"{"+ Arrays.stream(topics).collect(Collectors.joining(" , "))+"}");
            subscriber.subscribe(topics,qos);
        } catch (Exception e) {
            fail(e.getMessage());
        }

    }
    private void unsubscribe(String... topics) {
        subscribe(subscriber,topics);

    }
    private void unsubscribe(MqttClient subscriber,String... topics) {
        connect(subscriber);
        try {
            System.out.println("USUB "+subscriber.getCurrentServerURI()+"{"+ Arrays.stream(topics).collect(Collectors.joining(" , "))+"}");
            subscriber.unsubscribe(topics);
        } catch (Exception e) {
            fail(e.getMessage());
        }

    }
    private void connect(MqttClient client) {
        int i = 5;
        while (!client.isConnected() && i > 0)
            try {
                System.out.println("Trying, left tries:"+i);
                i--;
                System.out.println("CONN "+client.getCurrentServerURI());
                client.connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        if(i<=0)
            fail("Unable to connect to the MQTT broker!");
    }
    private Response execute(Request request) {
        try {
            return request.execute();
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        return null;
    }
    private HttpResponse collectHttpResponse(Response response) {
        try {
            return  response.returnResponse();
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        return null;
    }
    private Content collectBody(Response response) {
        try {
            return response.returnContent();
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        return null;
    }
    private <T> T  parse(String content, Class<? extends T> clas)  {
        try {
            return  mapper.readValue(content, clas);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }
    private <T> T  parse(byte[] content, Class<? extends T> clas)  {
        try {
            return  mapper.readValue(content, clas);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }
    private <T> T  parse(String content, TypeReference clas)  {
        try {
            return  mapper.readValue(content, clas);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }
    private <T> T  parse(byte[] content, TypeReference clas)  {
        try {
            return  mapper.readValue(content, clas);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }
    private byte[]  serialize(Object object)  {
        try {
            return  mapper.writeValueAsBytes(object);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }
    private URI testURL(String url)  {
        try {
            return  new URI(url);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());

        }
        return null;
    }

    @Override
    public void connectionLost(Throwable cause) {
        fail("connection lost");
        cause.printStackTrace();
    }
    int sensorN=0;
    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        synchronized (messages) {
           // System.out.println(topic + " counting " + messageN + " count1 " + message1N + " count2 " + message2N);
            if (topic.contains("OGC") && topic.contains("Datastream")) {
                Observation observation = parse(message.getPayload(), ObservationImpl.class);
                if (topic.contains("DPA") || topic.contains("LA")) {
                    if (topic.contains(counting)) {
                        messages.set(observation.getIntResult() - 1, true);
                     //   messages.remove(0);
                        messageN++;
                    } else if (topic.contains(count1)) {
                     //   messages.remove(0);
                        message1N++;
                    } else if (topic.contains(count2)) {
                     //   messages.remove(0);
                        message2N++;
                    } else if (topic.contains("average_temperature")) {
                        average = observation.getDoubleResult();
                    } else {
                        System.err.println("An agent message was not processed");
                        receivingFail = true;
                    }

                }
            }
            if(topic.contains("sensor")) {
                Observation observation = parse(message.getPayload(), ObservationImpl.class);
                System.out.println("number "+observation.getIntResult()+" count "+sensorN);
                arrived[0] = true;
                sensorN++;
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }
}
