import com.dtolabs.rundeck.plugins.notification.NotificationPlugin
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URL
import java.util.Scanner

// See http://rundeck.org/docs/developer/notification-plugin-development.html

/** See https://docs.opsgenie.com/docs/alert-api#section-create-alert
 * curl -XPOST 'https://api.opsgenie.com/v2/alerts' \
 *    --header 'Authorization: GenieKey 100a711f-eeca-4e23-b7a7-df8a3c8e3d03'
 *    -d '{
 *      "message" : "WebServer3 is down",
 *      "teams" : ["operations", "developers"]
 *     }'
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
    static String OPSGENIE_URL = 'https://api.opsgenie.com/v2/alerts'
    static String MESSAGE_TEMPLATE = '${job.status} [${job.project}] \"${job.name}\"'
    static String ALIAS_TEMPLATE = '${job.id}'
    static String DESCRIPTION_TEMPLATE = '${job.status} [${job.project}] \"${job.name}\" run by ${job.user} (#${job.execid}) [ ${job.href} ]'
    static String SOURCE_TEMPLATE = '${job.href}'
}

/**
 * Expands the a string using a predefined set of tokens
 */
def render(text, binding) {
    //defines the set of tokens usable in the subject configuration property
    // See http://rundeck.org/docs/developer/notification-plugin.html#execution-data for more.
    def tokens = [
            '${job.status}'             : binding.execution.status.toUpperCase(),
            '${job.project}'            : binding.execution.job.project,
            '${job.name}'               : binding.execution.job.name,
            '${job.group}'              : binding.execution.job.group,
            '${job.user}'               : binding.execution.user,
            '${job.href}'               : binding.execution.href,
            '${job.execid}'             : binding.execution.id.toString(),
            '${job.dateStartedUnixTime}': binding.execution.dateStartedUnixtime.toString(),
    ]
    if (text == null) {
        null
    } else {
        text.replaceAll(/(\$\{\S+?\})/) {
            if (tokens[it[1]]) {
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
def sendAlert(Map executionData, Map configuration, Boolean close = false) {
    def expandedMessage = render(configuration.message, [execution: executionData])
    def expandedDescription = render(configuration.description, [execution: executionData])
    def expandedSource = render(configuration.source, [execution: executionData])
    def expandedAlias = render(configuration.alias, [execution: executionData])
    def job_data = [
            message    : expandedMessage,
            description: expandedDescription,
            source     : expandedSource,
            alias      : expandedAlias,
            details    : [
                    job        : executionData.job.name,
                    group      : executionData.job.group,
                    description: executionData.job.description,
                    project    : executionData.job.project,
                    user       : executionData.user,
                    status     : executionData.status,
            ]
    ]
    if (configuration.proxy_host != null && configuration.proxy_port != null) {
        System.err.println("DEBUG: proxy_host=" + configuration.proxy_host)
        System.err.println("DEBUG: proxy_port=" + configuration.proxy_port)
        System.getProperties().put("proxySet", "true")
        System.getProperties().put("proxyHost", configuration.proxy_host)
        System.getProperties().put("proxyPort", configuration.proxy_port)
    }

    // Send the request.
    def endpoint = DEFAULTS.OPSGENIE_URL;
    if (close) {
        // TODO: Alias needs url escaping
        def encodedAlias = java.net.URLEncoder.encode(expandedAlias, "UTF-8")
        endpoint += "/${encodedAlias}/close?identifierType=alias"
        // TODO: Add auto-close note.
    }
    def url = new URL(endpoint)
    def connection = url.openConnection()
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Authorization", "GenieKey ${configuration.api_key}")
    connection.setRequestProperty("Accept", "application/json")
    connection.doOutput = true

    def writer = new OutputStreamWriter(connection.outputStream)
    def json = new ObjectMapper()
    String raw_data = null
    if (close) {
        raw_data = json.writeValueAsString([note: "Auto closing as job has since succeeded"])
    } else {
        raw_data = json.writeValueAsString(job_data)
    }
    System.err.println("DEBUG: request: " + raw_data)
    writer.write(raw_data)
    writer.flush()
    writer.close()
    connection.connect()

    // process the response.
    int response_code = connection.getResponseCode()
    if (response_code != 202) {
        system.err.println("Unexpected response from OpsGenie API: ${response_code}")
    }
    def httpResponseScanner = new Scanner(connection.getInputStream())
    while(httpResponseScanner.hasNextLine()) {
        println(httpResponseScanner.nextLine())
    }
}


rundeckPlugin(NotificationPlugin) {
    title = "OpsGenie"
    description = "Create an alert."
    configuration {
        message title: "Message",
                description: "Message. Can contain \${job.status}, \${job.project}, \${job.name}, \${job.group}, \${job.user}, \${job.execid}",
                defaultValue: DEFAULTS.MESSAGE_TEMPLATE,
                required: true

        description title: "Description",
                description: "Description.",
                defaultValue: DEFAULTS.DESCRIPTION_TEMPLATE,
                required: false

        alias title: "Alias",
                description: "Alias.",
                defaultValue: DEFAULTS.ALIAS_TEMPLATE,
                required: false

        source title: "Source",
                description: "Source.",
                defaultValue: DEFAULTS.SOURCE_TEMPLATE,
                required: false

        api_key title: "Integration API Key",
                description: "The API key",
                scope: "Project"

        proxy_host title: "Proxy host", description: "Outbound proxy",
                scope: "Project",
                defaultValue: null,
                required: false

        proxy_port title: "Proxy port",
                description: "Outbound proxy port",
                scope: "Project",
                defaultValue: null,
                required: false
    }
    onstart { Map executionData, Map configuration ->
        sendAlert(executionData, configuration)
        true
    }
    onfailure { Map executionData, Map configuration ->
        sendAlert(executionData, configuration)
        true
    }
    onsuccess { Map executionData, Map configuration ->
        sendAlert(executionData, configuration, true)
        true
    }

}
