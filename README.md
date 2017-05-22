Use this [notification](http://rundeck.org/docs/developer/notification-plugin-development.html)
plugin to send [alert](https://www.opsgenie.com/docs/web-api/alert-api#createAlertRequest)
events to your [OpsGenie](https://www.opsgenie.com) service.

The plugin requires one parameter:

* subject: This string will be set as the description for the generated incident.

Context variables usable in the subject line:

* `${job.status}`: Job execution status (eg, FAILED, SUCCESS).
* `${job.project}`: Job project name.
* `${job.name}`: Job name.
* `${job.group}`: Job group name.
* `${job.user}`: User that executed the job.
* `${job.execid}`: Job execution ID.

## Installation

Copy the groovy script to the plugins directory:

    cp src/OpsGenieNotification.groovy to $RDECK_BASE/libext

and start using it!

## Configuration

The plugin only requires the 'api_key' configuration entry. There are also a few optional configurations.

* api_key: This is the API Key to your service.

Configure the api_key in your project configuration by
adding an entry like so: $RDECK_BASE/projects/{project}/etc/project.properties

    project.plugin.Notification.OpsGenieNotification.api_key=xx123049e89dd45f28ce35467a08577yz

Or configure it at the instance level: $RDECK_BASE/etc/framework.properties

    framework.plugin.Notification.OpsGenieNotification.api_key=xx123049e89dd45f28ce35467a08577yz


### All options

|Option|Scope|Default|Required|Description|
|-|-|-|-|-|
|`api_key`|Any|None|Yes|Integration API Key|
|`message`|Any|`${job.status} [${job.project}] \"${job.name}\"`|Yes|Message template.|
|`description`|Any|`${job.status} [${job.project}] \"${job.name}\" run by ${job.user} (#${job.execid}) [${job.href}]`|No|Description template.|
|`alias`|Any|`'${job.execid}`|No|alias template.|
|`source`|Any|`${job.href}`|No|Source template.|
|`proxy_host`|Project|None|Yes|Your egress proxy host.|
|`proxy_port`|Project|None|If `proxy_host` is set|the port the network egress proxy accepts traffic on.|
