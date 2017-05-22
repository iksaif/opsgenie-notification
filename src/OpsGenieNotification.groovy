import com.dtolabs.rundeck.plugins.notification.NotificationPlugin;
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants;
import com.dtolabs.rundeck.core.plugins.configuration.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode

// See http://rundeck.org/docs/developer/notification-plugin-development.html

/** See https://www.opsgenie.com/docs/web-api/alert-api#createAlertRequest
 * curl -XPOST 'https://api.opsgenie.com/v1/json/alert' \
 *    -d '{
 *      "apiKey": "eb243592-faa2-4ba2-a551q-1afdf565c889",
 *      "message" : "WebServer3 is down",
 *      "teams" : ["operations", "developers"]
 *    }'
 *
 *  Fields:
 * - teams	List of team names which will be responsible for the alert. Team escalation policies are run to calculate
 *          which users will receive notifications. Teams which are exceeding the limit are ignored. 50 teams
 * - alias	Used for alert deduplication. A user defined identifier for the alert and there can be only one alert with
 *          open status with the same alias. Provides ability to assign a known id and later use this id to perform
 *          additional actions such as log, close, attach for the same alert.	512 chars
 * - description	This field can be used to provide a detailed description of the alert, anything that may not have
 *                  fit in the Message field.	15000 chars
 * - recipients	Optional user, group, schedule or escalation names to calculate which users will receive the
 *              notifications of the alert. Recipients which are exceeding the limit are ignored.	50 recipients
 * - actions	A comma separated list of actions that can be executed. Custom actions can be defined to enable users
 *              to execute actions for each alert. If Webhook Integration exists, webhook URL will be called when action
 *              is executed. Also if Marid Integration exists, actions will be posted to Marid. Actions will be posted
 *              to all existing bi-directional integrations too. Actions which are exceeding the number limit
 *              are ignored. Action names which are longer than length limit are shortened.	10 actions, 50 chars each
 * - source	Field to specify source of alert. By default, it will be assigned to IP address of incoming request	512 chars
 * - tags	A comma separated list of labels attached to the alert. You can overwrite Quiet Hours setting for urgent
 *          alerts by adding OverwriteQuietHours tag. Tags which are exceeding the number limit are ignored.
 *          Tag names which are longer than length limit are shortened.	20 tags, 50 chars each
 * - details	Set of user defined properties. This will be specified as a nested JSON map such as: "details" :
 *              {"prop1":"prop1Value", "prop2":"prop2Value"}	8000 chars
 * - entity	The entity the alert is related to.	512 chars
 * - user	Default owner of the execution. If user is not specified, the system becomes owner of the execution. 100 chars
 * - note	Additional alert note
*/

class DEFAULTS {
    static String OPSGENIE_URL = "https://api.opsgenie.com/v1/json/alert"
    static String MESSAGE_TEMPLATE = '${job.status} [${job.project}] \"${job.name}\"'
    static String ALIAS_TEMPLATE = '${job.execid}'
    static String DESCRIPTION_TEMPLATE = '${job.status} [${job.project}] \"${job.name}\" run by ${job.user} (#${job.execid}) [${job.href}]'
    static String SOURCE_TEMPLATE = '${job.href}'
}

/**
 * Expands the a string using a predefined set of tokens
 */
def render(text, binding) {
    //defines the set of tokens usable in the subject configuration property
    def tokens=[
        '${job.status}': binding.execution.status.toUpperCase(),
        '${job.project}': binding.execution.job.project,
        '${job.name}': binding.execution.job.name,
        '${job.group}': binding.execution.job.group,
        '${job.user}': binding.execution.user,
        '${job.href}': binding.execution.href,
        '${job.execid}': binding.execution.id.toString()
    ]
    if (text == null) {
      null
    } else {
      text.replaceAll(/(\$\{\S+?\})/){
        if(tokens[it[1]]){
	  tokens[it[1]]
        } else {
	  it[0]
        }
      }
    }
}

/**
 * Trigger an opsgenie alert.
 * @param executionData
 * @param configuration
 */
def sendAlert(Map executionData, Map configuration) {
    System.err.println("DEBUG: api_key="+configuration.api_key)
    def expandedMessage = render(configuration.message, [execution:executionData])
    def expandedDescription = render(configuration.description, [execution:executionData])
    def expandedSource = render(configuration.source, [execution:executionData])
    def expandedAlias = render(configuration.alias, [execution:executionData])
    def job_data = [
            apiKey: configuration.api_key,
            message: expandedMessage,
            description: expandedDescription,
            source: expandedSource,
            alias: expandedAlias,
            details:[
                    job: executionData.job.name,
                    group: executionData.job.group,
                    description: executionData.job.description,
                    project: executionData.job.project,
                    user: executionData.user,
                    status: executionData.status,
            ]
    ]
    if (configuration.proxy_host != null && configuration.proxy_port != null) {
        System.err.println("DEBUG: proxy_host="+configuration.proxy_host)
        System.err.println("DEBUG: proxy_port="+configuration.proxy_port)
        System.getProperties().put("proxySet", "true")
        System.getProperties().put("proxyHost", configuration.proxy_host)
        System.getProperties().put("proxyPort", configuration.proxy_port)
    }

    // Send the request.
    def url = new URL(DEFAULTS.OPSGENIE_URL)
    def connection = url.openConnection()
    connection.setRequestMethod("POST")
    connection.addRequestProperty("Content-type", "application/json")
    connection.doOutput = true
    def writer = new OutputStreamWriter(connection.outputStream)
    def json = new ObjectMapper()
    def raw_data = json.writeValueAsString(job_data)
    System.err.println("DEBUG: request: " + raw_data)
    writer.write(raw_data)
    writer.flush()
    writer.close()
    connection.connect()

    // process the response.
    def response = connection.content.text
    System.err.println("DEBUG: response: "+response)
    JsonNode jsnode= json.readTree(response)
    def status = jsnode.get("status").asText()
    if (! "success".equals(status)) {
        System.err.println("ERROR: OpsGenieNotification plugin status: " + status)
    }
}


rundeckPlugin(NotificationPlugin){
    title="OpsGenie"
    description="Create an alert."
    configuration{
        message title:"Message", description:"Message. Can contain \${job.status}, \${job.project}, \${job.name}, \${job.group}, \${job.user}, \${job.execid}", defaultValue:DEFAULTS.MESSAGE_TEMPLATE,required:true

        description title:"Description", description:"Description.", defaultValue:DEFAULTS.DESCRIPTION_TEMPLATE,required:false

        alias title:"Alias", description:"Alias.", defaultValue:DEFAULTS.ALIAS_TEMPLATE,required:false

        source title:"Source", description:"Source.", defaultValue:DEFAULTS.SOURCE_TEMPLATE,required:false

        api_key title:"Integration API Key", description:"The API key", scope:"Project"

        proxy_host title:"Proxy host", description:"Outbound proxy", scope:"Project", defaultValue:null, required:false

        proxy_port title:"Proxy port", description:"Outbound proxy port", scope:"Project", defaultValue:null, required:false
    }
    onstart { Map executionData,Map configuration ->
        sendAlert(executionData, configuration)
        true
    }
    onfailure { Map executionData, Map configuration ->
        sendAlert(executionData, configuration)
        // return success.
        true
    }
    onsuccess {
        sendAlert(executionData, configuration)
        true
    }

}
