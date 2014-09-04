package com.adobe.acs.dnb;

import com.day.cq.analytics.sitecatalyst.SitecatalystException;
import com.day.cq.analytics.sitecatalyst.SitecatalystHttpClient;
import com.day.cq.wcm.webservicesupport.Configuration;
import com.day.cq.wcm.webservicesupport.ConfigurationManager;
import org.apache.felix.scr.annotations.*;
import org.apache.felix.scr.annotations.Property;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.jcr.api.SlingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

@org.apache.felix.scr.annotations.Component(
        metatype = true,
        immediate = false,
        label="ACS Analytics Report Fetcher",
        description="Fetches a configured analytics report via configuration"
)
@Service
@Properties({
        @Property(name="scheduler.period", longValue = 86400, label = "Frequency/Seconds", description = "The frequency in seconds the report is queued"),
        @Property(name = "scheduler.concurrent", boolValue = false, propertyPrivate = true),
        @Property(name = "scheduler.runOn", value="LEADER", propertyPrivate = true)
})
public class AnalyticsReportScheduler implements Runnable {

    @Reference
    private SitecatalystHttpClient httpClient;

    @Reference
    private ConfigurationManager configManager;

    @Reference
    private SlingRepository repository;

    @Property(label = "Report Suite", description = "Can be configured in /system/console/configMgr")
    public static final String REPORT_SUITE_ID = "ars.rsid";
    private String reportSuiteId;

    @Property(label = "Number of Results", description = "The number of results to return" , value = "50")
    public static final String NUM_RESULTS = "ars.numresults";
    private String numResults;

    @Property(label = "Analytics Node Name", description = "The name of the analytics node that contains credentials")
    public static final String ANALYTICS_NODE = "ars.analyticsnode";
    private String analyticsNode;

    @Property(label = "Number of days", description = "The number of days to report on.",intValue = 5)
    public static final String NUMBER_OF_DAYS = "ars.noOfDays";
    private int numberOfDays = 5;

    Logger log = LoggerFactory.getLogger(this.getClass().getName());

    private Session adminSession = null;

    private Date now = new Date();
    SimpleDateFormat sdf = new SimpleDateFormat("YYYY-MM-dd");

    Calendar myCal = Calendar.getInstance();

    public void run() {

        log.info( "Analytics Report Scheduler firing." );
        requestReport();

    }

    /**
     * Method to the get site catalyst report given a payload.
     */

    private void requestReport(){

        myCal.add(Calendar.DATE, -numberOfDays);

        try{

            String reportPayload = "{\n" +
                    "                \"reportDescription\":{\n" +
                    "                \"reportSuiteID\":\"" + reportSuiteId + "\",\n" +
                    "                        \"dateFrom\":\"2014-08-25\",\n" +
                    "                        \"dateTo\":\"" + sdf.format(now) + "\",\n" +
                    "                        \"metrics\":[{\"id\":\"pageViews\"},{\"id\":\"reloads\"},{\"id\":\"entries\"},{\"id\":\"exits\"},{\"id\":\"averageTimeSpentOnPage\"}],\n" +
                    "                \"elements\":[{\"id\":\"page\", \"top\":\"" + numResults + "\"}]\n" +
                    "            }\n" +
                    "            }";

            adminSession = repository.loginAdministrative( null );

            Configuration siteCatConfig = configManager.getConfiguration( "/etc/cloudservices/sitecatalyst/" + analyticsNode );

            if( siteCatConfig != null ){
                String reportRequest = httpClient.execute( "Report.QueueRanked", reportPayload, siteCatConfig );

                /*
                    {"status":"queued","statusMsg":"Your report has been queued","reportID":114240512}
                    Get the report ID from the above JSON
                */

                JSONObject reportRequestJson = new JSONObject( reportRequest );
                String reportId = reportRequestJson.getString("reportID");
                log.debug( reportRequest );

                /*
                    After getting the report id, get the report status
                */

                boolean isReportReady = false;
                int i = 0;

                // 5 second delay and five tries before failing. You may have to tweak this for longer running reports
                do{
                    isReportReady = isReportReady( reportId, siteCatConfig );
                    i++;
                    log.debug( "isReportReady {}, i {}", isReportReady, i );
                    Thread.sleep( 5000 ); //Wait before calling again.
                } while ( !isReportReady && i <= 5 );

                if( isReportReady ){

                    /*
                       If report is ready, get the report and create a node under /var to save the report
                    */

                    String getReportPayload = "{\"reportID\" : \"" + reportId + "\"}";
                    String siteCatReport = httpClient.execute( "Report.GetReport", getReportPayload, siteCatConfig );
                    log.debug( siteCatReport );
                    createReportNode( siteCatReport );

                } else {
                    log.info( "Report wasn't returned by analytics within configured time frame.");
                }
            }

        } catch ( Exception e ){
            log.error( "Error in AnalyticsReportScheduler", e );
        } finally {

            if( adminSession != null ){
                adminSession.logout();
                adminSession = null;
            }

        }

        if( adminSession != null ){
            adminSession.logout();
            adminSession = null;
        }


    }

    /**
     * Method to check if the site catalyst report is ready
     * @param reportId ID of the report whose status needs to be checked
     * @param configuration Site Catalyst Configuration
     * @return true if the report is ready
     * @throws SitecatalystException
     * @throws JSONException
     */

    boolean isReportReady( String reportId, Configuration configuration ) throws SitecatalystException, JSONException {
        boolean isReady = false;

        String reportStatusPaylod = "{\"reportID\" : \"" + reportId + "\"}";
        String reportStatus = httpClient.execute( "Report.GetStatus", reportStatusPaylod, configuration );
        log.debug( reportStatus );

        JSONObject reportStatusJson = new JSONObject( reportStatus );
        String status = reportStatusJson.getString( "status" );
        if( status.equalsIgnoreCase( "done" ) ){
            isReady = true;
        }

        return isReady;
    }

    /**
     * Method to create a CRX node with the report. This is done to minimize the HTTP calls to Site Catalyst
     * @param nodeData String representation of the report that will go in the CRX node.
     * @throws javax.jcr.RepositoryException
     */

    private void createReportNode( String nodeData ) throws RepositoryException {
        log.debug( "Creating Node Under /var/acs-analytics" );

        InputStream inputStream = new ByteArrayInputStream( nodeData.getBytes() );
        ValueFactory valueFactory = adminSession.getValueFactory();
        Binary contentValue = valueFactory.createBinary( inputStream );

        Node varNode = JcrUtils.getOrAddNode(adminSession.getRootNode(), "var", "sling:Folder"); //create /var node if it doesnt exist

        Node reportNode = JcrUtils.getOrAddNode( varNode, "acs-analytics", "sling:Folder" ); //create /var/feeds node if it doesnt exist already

        Node reportFile = JcrUtils.getOrAddNode( reportNode, "analytics-report.json" , "nt:file" );
        Node jcrContentNode = JcrUtils.getOrAddNode( reportFile, "jcr:content", "nt:resource" );
        jcrContentNode.setProperty( "jcr:mimeType", "application/json" );
        jcrContentNode.setProperty( "jcr:data", contentValue );

        adminSession.save();
    }

    @Activate
    protected void activate(final Map<String, Object> config) {
        configure(config);
        log.debug("activated");
        log.debug(sdf.format(now));

        log.debug(sdf.format(myCal.getTime()));
    }

    private void configure(final Map<String, Object> config) {
        this.reportSuiteId = PropertiesUtil.toString(config.get(REPORT_SUITE_ID), null);
        this.numResults = PropertiesUtil.toString(config.get(NUM_RESULTS), null);
        this.analyticsNode = PropertiesUtil.toString(config.get(ANALYTICS_NODE), null);
        this.numberOfDays = PropertiesUtil.toInteger(config.get(NUMBER_OF_DAYS), 5);
        log.debug("configure: reportSuiteId='{}'", this.reportSuiteId);
        log.debug("configure: numResults='{}'", this.numResults);
        log.debug("configure: analyticsNode='{}'", this.analyticsNode);
        log.debug("configure: numberOfDays='{}'", this.numberOfDays);
    }


}

