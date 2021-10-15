import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.v2.sharing.CreateSharedLinkWithSettingsErrorException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static DbxClientV2 client; //to connect to DropBox
    private static ObjectMapper mapper = new ObjectMapper(); //for processing json
    private static ObjectNode topics; //to hold topics and their respective policies

    public static void main(String[] args) {
        try {
            List<String> parties = new ArrayList<>(); //Make list to store parties
            List<List<String>> members = new ArrayList<>(); //make list to store different information properties
            //about the MPs
            for (int i = 0; i < 5; i++) members.add(new ArrayList<String>()); //for each different property
            String[] htmlKeys = { //HTML page keys for MP's properties
                    "/mp/", //for their ID
                    "party",
                    "name",
                    "constituency"};

            /*construct lists of MPs' properties*/
            BufferedReader br = null; //to read online data
            try {
                URL url = new URL("https://www.theyworkforyou.com/mps/"); //connect to website
                br = new BufferedReader(new InputStreamReader(url.openStream())); //read data

                String line;
                while ((line = br.readLine()) != null) { //reading each line of the HTML
                    for (int i = 0; i < 4; i++) { //for each property
                        //if line has required currently-iterated property
                        if (line.contains((i == 0 ? "" : "people-list__person__") + htmlKeys[i])) {
                            //establish property-matching regex
                            Matcher found = Pattern.compile((i == 0) ? "\\d{5}" : ">.+<").matcher(line);
                            found.find(); //find property
                            String s = found.group(); //get property
                            if (i > 0) { //if getting MP ID
                                s = s.substring(1, s.length() - 1); //remove unnecessary characters
                                if (i == 2) { //if getting their name
                                    //split to first name and surname
                                    String[] nameSplit = s.split(" ", 2);
                                    s = nameSplit[0];
                                    //add surname to separate child list
                                    members.get(3).add(nameSplit[1]);
                                } else if (i == 1) { //MP party
                                    if (s.equals("Labour/Co-operative")) s = "Labour"; //remove unnecessary parts
                                    if (!parties.contains(s)) parties.add(s); //compiling list of unique parties
                                }
                            }
                            members.get(i == 3 ? 4 : i).add(s); //so constituency list doesn't intrude on the list
                            //of surnames
                        }
                    }
                }
            } finally {
                if (br != null) br.close(); //to prevent data leakage
            }

            if (!members.get(0).isEmpty() && !members.get(4).isEmpty()) { //check if web page showing the right content
                //(is cleared during election time)

                /*create JSON from MPs object lists*/
                ObjectNode MPs = mapper.createObjectNode(); //make new object
                String[] attribs = {"id", "first", "surname", "seat"}; //array of attributes
                for (int i = 0; i < members.get(0).size(); i++) { //iterate through all MPs
                    String party = members.get(1).get(i); //get the currently-iterated MP's party
                    if (!MPs.has(party)) MPs.putArray(party); //add party array to object if not already there
                    ArrayNode partyArray = MPs.withArray(party); //get party array
                    ObjectNode member = mapper.createObjectNode(); //new object for MP
                    //add MP's attributes to object
                    for (int j = 0; j < 4; j++) member.put(attribs[j], members.get(j == 0 ? 0 : j + 1).get(i));
                    partyArray.add(member); //add MP to array of their respective party
                }

                System.out.println("Number of MPs: " + members.get(0).size()); //total number of MPs made

                //read file of policies and their corresponding socioeconomic values,
                //separating each section (topic) by an astrix
                Scanner scanner = new Scanner(new File("src/main/policies.txt")).useDelimiter("\\*");
                //array of policy topics
                String[] topicNames = {
                        "Social issues",
                        "Foreign policy",
                        "Welfare",
                        "The Economy",
                        "The Constitution",
                        "Home affairs",
                        "Environment and transport",
                        "Misc"
                };

                int counter = 0;
                topics = mapper.createObjectNode(); //make json object
                while (scanner.hasNext()) { //iterate each section (topic)
                    ArrayNode topicsArray = mapper.createArrayNode(); //make array for holding topic's policies
                    String topic = scanner.next(); //get topic's policies
                    //separate the policies
                    String[] topicPolicies = topic.split(System.getProperty("line.separator"));
                    for (String policy : topicPolicies) { //for each loop on the policies
                        //split policy into properties of its title, economic value and social value (the latter two
                        // representing either -1, 0 or 1)
                        String[] policyProps = policy.split(";");
                        //add new object to array, comprising its respective properties
                        topicsArray.add(mapper.createObjectNode()
                                   .put("policy", policyProps[0])
                                   .put("economic", Integer.parseInt(policyProps[2]))
                                   .put("social", Integer.parseInt(policyProps[1])));
                    }
                    topics.put(topicNames[counter], topicsArray); //add array of topic's policies to object of
                    // all topics
                    counter++; //increment for next topic
                }

                //Connect to DropBox, via access code
                DbxRequestConfig config = DbxRequestConfig.newBuilder("JSONBigTent/1.0")
                        .withUserLocale(Locale.getDefault().toString()).build();
                client = new DbxClientV2(config, "CODE GOES HERE");

                System.out.println("Linked account: " + client.getClass().getCanonicalName());
                //show number of unique shareable links
                System.out.println("Links: " + client.sharing().listSharedLinks().getLinks().size());
                uploadFile(topics, "thepolicies"); //upload policies json

                ObjectNode partiesVotes = mapper.createObjectNode(); //for later use (parties' records)
                int MPCounter = 0; //to keep count of all MPs (not just within a specific party)
                for (String party : parties) { //for each loop of each party
                    if (!party.matches("Sinn Féin")) { //ensure not an abstentionist party
                        ArrayNode partyArray = MPs.withArray(party); //get party array of MP objects
                        for (JsonNode partyMPJson : partyArray) { //Iterate MPs

                            //Read (stream) JSON file
                        /*Need to reopen connection as member-list-iterated through,
                        else stream prematurely closed by Heroku*/
                            //establish URL connection with required authentication properties
                            URL url = new URL("https://www.publicwhip.org.uk/data/popolo/policies.json");
                            URLConnection connection = url.openConnection();
                            connection.setRequestProperty("User-Agent",
                                    "Mozilla 5.0 (Windows; U; " + "Windows NT 5.1; en-US; rv:1.8.0.11) ");
                            InputStream connectionStream = connection.getInputStream(); //get bytes stream
                            JsonFactory jsonFactory = new JsonFactory(); //configurs and constructs json read/write
                            //parses json from byte input stream
                            JsonParser jsonParser = jsonFactory.createParser(connectionStream);

                            ObjectNode MP = mapper.createObjectNode(), //object to hold MP's votes + positioning
                                    MPRecord = mapper.createObjectNode(), //object to hold MP's votes specifically
                                    partyMP = (ObjectNode) partyMPJson; //get MP's properties (name, seat, etc)
                            String MPId = partyMP.get("id").textValue(); //get MP's url-identifier
                            double economic = 0, social = 0; //socioeconomic-position-pinpointing variables
                            //to hold total number of economically and socially oriented votes
                            int econTotal = 0, socialTotal = 0;

                            /*for (String topicName : topicNames)
                                MPRecord.putArray(topicName); //Add topics to MP's record*/
                            if (jsonParser.nextToken() != JsonToken.START_ARRAY) //ensure online json file read properly
                                throw new IllegalStateException("Not an array");
                            //iterate through object; read from start to matching end of object
                            //go through each policy MP has voted on
                            while (jsonParser.nextToken() == JsonToken.START_OBJECT) {
                                //get currently-streamed tree-structure ObjectNode
                                JsonNode node = mapper.readTree(jsonParser);
                                //get policy name, and remove trailing and leading spaces
                                String policy = node.path("title").textValue().trim();
                                //traverse json to get to different motion votes
                                JsonNode aspects = node.path("aspects");

                                //variables for later use in getting percentage support per policy
                                double level = 0; //total support points (accumulating points of value 0, 0.5 and 1)
                                double votesIn = 0; //votes of specific policy that've been voted on by MP

                                String topic = "";
                                for (int i = 0; i < topics.size(); i++) { //iterate topics
                                    //Binary Search to verify if the list contains variable 'topic'
                                    if (search(topics.get(topicNames[i]), policy, 0,
                                            topics.get(topicNames[i]).size() - 1) != -1) {
                                        topic = topicNames[i]; //policy's topic found
                                        break; //no need to iterate the rest
                                    }
                                }
                                if (topic.equals(""))
                                    topic = "Misc"; //if not found a topic for it, miscellaneous policy

                                for (int j = 0; j < aspects.size(); j++) {//iterate votes (motions) of the policy
                                    JsonNode motion = aspects.get(j).path("motion"); //get motion json

                                    //how to vote to be in support of policy (in favour, against or abstaining -
                                    // aye(3), no or absent/both,
                                    // immediately preceded by "tell" if the MP was a 'teller')
                                    String policy_vote = motion.path("policy_vote").textValue();
                                    String motionId = motion.path("id").textValue(); //unique identifier for vote

                                    //remove unnecessary character from way of voting recorded
                                    if (policy_vote.contains("3"))
                                        policy_vote = policy_vote.substring(0, policy_vote.length() - 1);
                                    else if (policy_vote.equals("both"))
                                        policy_vote = "absent"; //establish uniform value
                                    JsonNode votes = motion.path("vote_events").get(0).path("votes"); //get votes of
                                    //all MPs for the policy
                                    for (int k = 0; k < votes.size(); k++) { //iterate all MPs' votes to find
                                        //required MP's vote
                                        JsonNode MPVote = votes.get(k); //get currently iterated MP's vote
                                        //if MP matches the required MP
                                        if (MPVote.path("id").textValue().equals("uk.org.publicwhip/person/" + MPId)) {
                                            //Given that MP now found to have voted on a motion of this topic,
                                            // add said topic to MP's record (only added as found to avoid blank
                                            // topics)
                                            if (!MPRecord.has(topic)) MPRecord.putArray(topic);
                                            votesIn++; //increase vote count of required MP on this policy
                                            String option = MPVote.path("option").textValue(); //get their vote
                                            //if their vote matches the policy vote (in favour or against the motion),
                                            //increase their support for it by 1
                                            //else if they abstain, increase by half a point
                                            if (option.matches("(tell|)" + policy_vote)) level++;
                                            else if (option.equals("absent")) level += 0.5;

                                            /*Building up the parties' general voting pattern on motions*/
                                            //if MP isn't without a party with >1 member
                                            if (!party.equals("Independent") && partyArray.size() > 1) {
                                                //if the party doesn't exist in parties object, create such an object
                                                if (!partiesVotes.has(party)) partiesVotes.putObject(party);
                                                //get the party's object
                                                ObjectNode partyObj = partiesVotes.with(party);
                                                //if the topic doesn't exist in the party's object,
                                                // create such an object
                                                if (!partyObj.has(topic)) partyObj.putObject(topic);
                                                //get the topic's object
                                                ObjectNode topicObj = partyObj.with(topic);
                                                //if the policy doesn't exist in the topic's object,
                                                // create such an object
                                                if (!topicObj.has(policy)) topicObj.putObject(policy);
                                                //get the policy's object
                                                ObjectNode policyObj = topicObj.with(policy);
                                                //if the motion doesn't exist in the policy's object,
                                                // create such an object
                                                if (!policyObj.has(motionId)) policyObj.putObject(motionId);
                                                //get the motion's object
                                                ObjectNode voteObj = policyObj.with(motionId);

                                                //the different ways a motion can be voted on
                                                String[] ways = {"for", "absent", "against"};
                                                //iterate through the ways; if motion object doesn't have any one of
                                                //these ways present as integers, add them to it
                                                for (String way : ways) if (!voteObj.has(way)) voteObj.put(way, 0);
                                                //if currently-iterated ("required") MP voted on the motion in the way
                                                //that is in favour of its policy, the count of the party's MPs voting
                                                //like that is increased by 1; likewise for those voting against policy
                                                //or being neutral (by abstaining) on it
                                                String which = option.matches("(tell|)" + policy_vote) ? "for"
                                                        : (option.equals("absent") ? option : "against");
                                                voteObj.put(which, voteObj.get(which).intValue() + 1);
                                            }
                                            break; //MP been found, so no need to iterate through the rest
                                        }
                                    }
                                }
                                //only execute code if MP has voted/abstained (i.e. been an MP at the time)
                                // on motions relating to this policy
                                if (votesIn != 0) {
                                    //called and passed values in order to help calculate MP's position
                                    Socioeconomic socioeconomic = updateSocioeconomic(
                                            true,
                                            social,
                                            economic,
                                            level,
                                            votesIn,
                                            MPRecord,
                                            topic,
                                            policy,
                                            econTotal,
                                            socialTotal);
                                    social = socioeconomic.getSoc(); //get MP's undivided social position
                                    economic = socioeconomic.getEcon(); //get MP's undivided economic position
                                    socialTotal = socioeconomic.getSocTotal(); //total number of social votes
                                    econTotal = socioeconomic.getEconTotal(); //total number of economic votes
                                }
                            }
                            if (!MPRecord.isEmpty()) { //only execute if MP has voted/abstained on anything
                                //if MP has voted/abstained on economic/social-nature policies
                                //divided by twice the totals in order to account for the
                                // policy % movement in the opposite
                                //direction (from 1 - policy support), which is necessary as part of the
                                // vector movement
                                if (econTotal != 0) economic = economic / (2 * econTotal);
                                if (socialTotal != 0) social = social / (2 * socialTotal);
                                //add to MP object in MPs list
                                partyMP.put("economic", economic)
                                       .put("social", social);
                                //add to MP's json page
                                MP.put("economic", economic)
                                  .put("social", social);
                                MP.put("votes", MPRecord); //add MPs record to their json
                                uploadFile(MP, MPId); //upload MP's json
                                try { //create shareable url for their json based on their ID
                                    client.sharing().createSharedLinkWithSettings("/" + MPId + ".json");
                                } catch (CreateSharedLinkWithSettingsErrorException ignored) { //if already made
                                }
                            }
                            System.out.println(MPCounter); //log count
                            MPCounter++; //increment
                            //close streams to prevent data leakage
                            jsonParser.close();
                            connectionStream.close();
                        }

                        //no point executing this if party has only one MP, as in that case party position is exactly
                        //equal to the sole MP's
                        if (!party.equals("Independent") && partyArray.size() > 1) {
                            //establish political position-pinpointing-needed variables
                            double economic = 0, social = 0;
                            int econTotal = 0, socTotal = 0;
                            //get each parties' topics
                            ObjectNode partyRecord = partiesVotes.with(party);
                            Iterator<String> topicItr = partyRecord.fieldNames();
                            while (topicItr.hasNext()) { //iterate through topics
                                String topic = topicItr.next(); //get current topic
                                ObjectNode topicObj = partyRecord.with(topic); //get topic json, holding policies

                                Iterator<String> policyItr = topicObj.fieldNames();
                                while (policyItr.hasNext()) { //iterate through policies
                                    String policy = policyItr.next(); //policy
                                    //variables to use to calculate % support for policy
                                    double supportLevel = 0;
                                    int votesIn = 0;
                                    ObjectNode policyObj = topicObj.with(policy); //get json object, holding votes

                                    Iterator<String> votesItr = policyObj.fieldNames();
                                    while (votesItr.hasNext()) { //iterate through votes in policy
                                        String vote = votesItr.next(); //get current vote/motion
                                        ObjectNode voteObj = policyObj.with(vote); //json object
                                        //increase policy support if most MPs of the party in favour;
                                        //partially increase if most abstained
                                        if (voteObj.get("for").intValue() > voteObj.get("against").intValue() &&
                                                voteObj.get("for").intValue() > voteObj.get("absent").intValue())
                                            supportLevel++;
                                        else if (voteObj.get("absent").intValue() > voteObj.get("for").intValue() &&
                                                voteObj.get("absent").intValue() > voteObj.get("for").intValue())
                                            supportLevel += 0.5;
                                        votesIn++; //increment reflects vote
                                    }
                                    if (votesIn != 0) { //if parties MPs have voted on the policy at all,
                                        //adjust socioeconomic position and reflect changes in the variables
                                        Socioeconomic socioeconomic = updateSocioeconomic(
                                                false,
                                                social,
                                                economic,
                                                supportLevel,
                                                votesIn,
                                                topicObj,
                                                topic,
                                                policy,
                                                econTotal,
                                                socTotal);
                                        economic = socioeconomic.getEcon();
                                        social = socioeconomic.getSoc();
                                        econTotal = socioeconomic.getEconTotal();
                                        socTotal = socioeconomic.getSocTotal();
                                    }
                                }
                            }
                            //same reasoning to MP-specific positioning
                            if (econTotal != 0) economic = economic / (2 * econTotal);
                            if (socTotal != 0) social = social / (2 * socTotal);
                            partyRecord.put("economic", economic)
                                       .put("social", social);
                        }
                    }
                }
                //having compiled all the requried data and finished the loop, upload MP list and party list files
                uploadFile(MPs, "MPs");
                uploadFile(partiesVotes, "partyish");

                ListFolderResult result = client.files().listFolder(""); //get all files
                while (true) {
                    for (Metadata metadata : result.getEntries()) { //iterate files
                        String filename = metadata.getName().replace(".json", ""); //get file name,
                        //without extension
                        //check if MP file is that of an MP currently in parliament (relevant when MP resigns or
                        // post-election)
                        if (!filename.matches("thepolicies|MPs|partyish") && !members.get(0).contains(filename)) {
                            //if not, delete
                            client.files().deleteV2(metadata.getPathLower());
                            System.out.println("Deleted: " + filename);
                        }
                    }

                    if (!result.getHasMore()) break; //end loop when no more to loop through
                    //paginate through all files and retrieve updates to the folder
                    result = client.files().listFolderContinue(result.getCursor());
                }
            } else System.out.println("Cannot execute code"); //web data unavailable
        } catch (IOException | DbxException e) { //connection or processing error
            e.printStackTrace(); //full details
        }
    }

    public static void uploadFile(ObjectNode obj, String path) throws IOException, DbxException {
        String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj); //well-readable format
        System.out.println(content);
        InputStream file = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)); //byte stream
        //upload to DropBox at provided path
        client.files().uploadBuilder("/" + path + ".json").withMode(WriteMode.OVERWRITE).uploadAndFinish(file);
    }

    //to update MP's (economic, social) position after having found their support level for a particular policy
    public static Socioeconomic updateSocioeconomic(
            boolean MPmode, //true if updating MP's positioning, false if updating a party's
            double social, //current undivided cumulative social positon
            double economic, //likewise economically
            double level, //policy support level
            double votesIn, //how many votes of the said policy the MP has been in parliament for
            ObjectNode object, //reference object to hold policy
            String topic, //policy's topic
            String policy,
            int econTotal, //total number of economically-inclined policies overall
            int socialTotal) { //total number of socially-inclined policies overall
            double div = level / votesIn; //calculate decimal support
            String agreement = new BigDecimal(div * 100).round(new MathContext(3))
                    .stripTrailingZeros().toPlainString() + "%"; //get % support to 3 significant figures
            if (MPmode) //add policy and its support level to object of its respective topic
                object.withArray(topic).add(mapper.createObjectNode()
                        .put("policy", policy)
                        .put("percent", agreement));
            else object.put(policy, agreement); //add straight to party's object

            if (!topic.equals("Misc")) { //ensure policy is accounted for as topical and thus it can have a
                //socioeconomic value
                //BinarySearch to find policy position within overall by-topic list column of their economic values
                //in order to retrieve policy's value (0, -1 or 1)
                int econPos = topics.get(topic)
                        .get(search(topics.get(topic), policy, 0, topics.get(topic).size() - 1))
                        .get("economic").intValue();

                //BinarySearch to find policy position within overall by-topic list column of their social values
                //in order to retrieve policy's value (0, -1 or 1)
                int socPos = topics.get(topic)
                        .get(search(topics.get(topic), policy, 0, topics.get(topic).size() - 1))
                        .get("social").intValue();

                //ensure policy causes movement in user position - is actually inclined economically or socially
                if (econPos != 0) {
                    //multiple % support for policy by -1 or +1 (depending on policy value); also subtract from this
                    //the % in opposition to the policy (100% - % support) multiplied by the negative/opposite of the
                    //assigned -1/+1 value - to represent the times the MP/party has been in opposition to the policy,
                    //vector-like modelling
                    economic += (div * econPos - (1 - div) * econPos) * 10;
                    econTotal++; //increment number of economically-inclined policies the MP/party has voted on
                }
                //likewise
                if (socPos != 0) {
                    social += (div * socPos - (1 - div) * socPos) * 10;
                    socialTotal++;
                }
            }
        return new Socioeconomic(social, economic, socialTotal, econTotal);
    }

    //BinarySearch; returns item position
    public static int search(JsonNode list, String toFind, int first, int last) {
        if (last >= first) { //ensure in range
            int centre = first + (last - first) / 2; //midpoint
            toFind = toFind.toLowerCase(); //to prevent ASCII case differentials leading to erroneous operation
            String policy = list.get(centre).get("policy").textValue().toLowerCase(); //policy name

            if (toFind.equals(policy)) return centre; //found
            else if (toFind.compareTo(policy) > 0) return search(list, toFind, centre + 1, last); //in upper half
            //of currently-checked list range
            else if (toFind.compareTo(policy) < 0) return search(list, toFind, first, centre - 1); //in lower half
        }
        return -1; //not found
    }

}