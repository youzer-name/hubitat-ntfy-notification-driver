metadata {
    definition(name: "NTFY Notifier Driver", namespace: "graftechnology", author: "Graf Technology, LLC", version: "1.0.0") {
        capability "Notification"

        command "testConnection"

        attribute "lastNotificationStatus", "string"
        attribute "lastNotificationTime", "string"
        attribute "connectionStatus", "string"
    }

    preferences {
        input name: "ntfyProtocol", type: "enum", title: "Protocol", options: ["https", "http"], defaultValue: "https", required: true
        input name: "ntfyHost", type: "text", title: "NTFY Host (e.g., ntfy.sh or yourdomain.com)", defaultValue: "ntfy.sh", required: true
        input name: "ntfyTopic", type: "text", title: "Topic", defaultValue: "hubitat", required: true
        input name: "ntfyTitle", type: "text", title: "Optional Title", defaultValue: "Hubitat", required: false
        input name: "ntfyPriority", type: "enum", title: "Optional Priority", defaultValue: "3", required: false,
              options: [null:"Default", "1":"Min", "2":"Low", "3":"Default", "4":"High", "5":"Max"]
        input name: "ntfyTags", type: "text", title: "Optional Tags (e.g., warning,house,fire - emojis auto-convert)", required: false
        input name: "ntfyClickAction", type: "enum", title: "Optional Click Action", defaultValue: "none", required: false,
              options: ["none":"None", "view":"Open URL", "http":"HTTP Request"]
        input name: "ntfyActionUrl", type: "text", title: "Action URL (required if click action selected)", required: false
        input name: "ntfyAttachUrl", type: "text", title: "Optional Attachment URL (image/file to attach)", required: false
        input name: "ntfyAttachFilename", type: "text", title: "Optional Attachment Filename (if URL provided)", required: false
        input name: "ntfyIconUrl", type: "text", title: "Optional Custom Icon URL", required: false
        input name: "ntfySequenceId", type: "text", title: "Optional Sequence ID (for replacing/clearing notifications)", required: false
        input name: "ntfyMarkdown", type: "bool", title: "Enable Markdown Formatting", defaultValue: false, required: false
        input name: "ntfyScheduling", type: "enum", title: "Optional Message Scheduling", defaultValue: "none", required: false,
              options: ["none":"Send Immediately", "delay":"Delay by Minutes", "at":"Send at Specific Time"]
        input name: "ntfyDelayMinutes", type: "number", title: "Delay Minutes (if delay selected)", required: false
        input name: "ntfyScheduleTime", type: "text", title: "Schedule Time (YYYY-MM-DD HH:MM:SS if 'at' selected)", required: false
        input name: "ntfyUsername", type: "text", title: "Username (for self-hosted servers with auth or NTFY Pro)", required: false
        input name: "ntfyPassword", type: "password", title: "Password (for self-hosted servers with auth or NTFY Pro)", required: false
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
    }
}

void installed() {
    logDebug "NTFY Notifier installed"
    initializeState()
}

void updated() {
    logDebug "NTFY Notifier updated"
    initializeState()
}

void initialize() {
    logDebug "NTFY Notifier initialized"
    initializeState()
}

void initializeState() {
    sendEvent(name: "lastNotificationStatus", value: "Not sent")
    sendEvent(name: "lastNotificationTime", value: "Never")
    sendEvent(name: "connectionStatus", value: "Unknown")
    logDebug "State initialized"
}

void deviceNotification(String msg) {
    logDebug "deviceNotification called with message: '${msg}'"

    // Input validation
    def validationResult = validateConfiguration()
    if (!validationResult.valid) {
        def errorMsg = "Notification failed - Configuration error: ${validationResult.error}"
        log.error errorMsg
        updateNotificationState("Failed", errorMsg)
        return
    }

    if (!msg || msg.trim().isEmpty()) {
        def errorMsg = "Notification failed - Message cannot be empty"
        log.error errorMsg
        updateNotificationState("Failed", errorMsg)
        return
    }

    def overrideKeys = [
        "topic", "title", "priority", "tags", "clickaction", "actionurl", "attachmenturl", "attachmentfilename", "iconurl", "sequenceid", "message"
    ]
    def jsonOverride = null
    try {
        jsonOverride = groovy.json.JsonSlurper.newInstance().parseText(msg)
    } catch (Exception e) {
        jsonOverride = null
    }

    def useOverride = false
    if (jsonOverride instanceof Map) {
        // Check if at least one valid key is present
        useOverride = overrideKeys.any { k -> jsonOverride.containsKey(k) }
    }

    def uri
    def headers
    def body

    if (useOverride) {
        // Build URI and headers using overrides
        uri = "${ntfyProtocol}://${ntfyHost.trim()}/${jsonOverride.topic ?: ntfyTopic.trim()}"
        headers = [:]
        if (jsonOverride.title) headers["Title"] = jsonOverride.title
        if (jsonOverride.priority) headers["Priority"] = jsonOverride.priority
        if (jsonOverride.tags) headers["Tags"] = jsonOverride.tags
        if (jsonOverride.clickaction && jsonOverride.actionurl) {
            if (jsonOverride.clickaction == "open_url" || jsonOverride.clickaction == "view") {
                headers["Click"] = jsonOverride.actionurl
            } else if (jsonOverride.clickaction == "http_request" || jsonOverride.clickaction == "http") {
                headers["Actions"] = "http, Open, ${jsonOverride.actionurl}"
            }
        }
        if (jsonOverride.attachmenturl) headers["Attach"] = jsonOverride.attachmenturl
        if (jsonOverride.attachmentfilename) headers["Filename"] = jsonOverride.attachmentfilename
        if (jsonOverride.iconurl) headers["Icon"] = jsonOverride.iconurl
        if (jsonOverride.sequenceid) headers["X-Sequence-Id"] = jsonOverride.sequenceid
        // Use authentication if configured
        if (ntfyUsername && ntfyUsername.trim() && ntfyPassword && ntfyPassword.trim()) {
            def credentials = "${ntfyUsername.trim()}:${ntfyPassword}".bytes.encodeBase64().toString()
            headers["Authorization"] = "Basic ${credentials}"
        }
        // Use markdown if enabled
        if (ntfyMarkdown) headers["Markdown"] = "yes"
        // Scheduling not supported in override
        body = jsonOverride.message ?: msg
    } else {
        uri = buildNotificationUri()
        headers = buildHeaders()
        body = msg.trim()
    }

    def params = [
        uri: uri,
        body: body,
        headers: headers,
        contentType: "text/plain",
        timeout: 30
    ]

    logDebug "Sending notification to: ${uri}"
    logDebug "Headers: ${headers}"
    logDebug "Message body: '${body}'"

    try {
        httpPost(params) { resp ->
            logDebug "HTTP Response - Status: ${resp.status}, Headers: ${resp.headers}"

            if (resp.status >= 200 && resp.status < 300) {
                def successMsg = "Notification sent successfully (HTTP ${resp.status})"
                logDebug successMsg
                updateNotificationState("Success", successMsg)
                sendEvent(name: "connectionStatus", value: "Connected")
            } else {
                def errorMsg = "Notification failed - Server returned HTTP ${resp.status}"
                log.warn errorMsg
                updateNotificationState("Failed", errorMsg)
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        def errorMsg = handleHttpError(e)
        log.error errorMsg
        updateNotificationState("Failed", errorMsg)
        sendEvent(name: "connectionStatus", value: "Error")
    } catch (java.net.ConnectException e) {
        def errorMsg = "Notification failed - Cannot connect to server '${ntfyHost}'. Check host and network connectivity."
        log.error errorMsg
        updateNotificationState("Failed", errorMsg)
        sendEvent(name: "connectionStatus", value: "Disconnected")
    } catch (java.net.UnknownHostException e) {
        def errorMsg = "Notification failed - Unknown host '${ntfyHost}'. Check the hostname."
        log.error errorMsg
        updateNotificationState("Failed", errorMsg)
        sendEvent(name: "connectionStatus", value: "Disconnected")
    } catch (java.net.SocketTimeoutException e) {
        def errorMsg = "Notification failed - Connection timeout. Server may be slow or unreachable."
        log.error errorMsg
        updateNotificationState("Failed", errorMsg)
        sendEvent(name: "connectionStatus", value: "Timeout")
    } catch (Exception e) {
        def errorMsg = "Notification failed - Unexpected error: ${e.message}"
        log.error errorMsg
        logDebug "Full exception details: ${e}"
        updateNotificationState("Failed", errorMsg)
        sendEvent(name: "connectionStatus", value: "Error")
    }
}

void testConnection() {
    log.info "Testing NTFY connection..."

    // Input validation
    def validationResult = validateConfiguration()
    if (!validationResult.valid) {
        def errorMsg = "Connection test failed - Configuration error: ${validationResult.error}"
        log.error errorMsg
        sendEvent(name: "connectionStatus", value: "Configuration Error")
        return
    }

    def testMessage = "Connection test from Hubitat at ${new Date().format('yyyy-MM-dd HH:mm:ss')}"
    def uri = buildNotificationUri()
    def headers = buildHeaders()

    // Override tags for test message to show it's a test
    headers["Tags"] = "white_check_mark,test_tube"

    def params = [
        uri: uri,
        body: testMessage,
        headers: headers,
        contentType: "text/plain",
        timeout: 30
    ]

    logDebug "Testing connection to: ${uri}"
    logDebug "Test headers: ${headers}"

    try {
        httpPost(params) { resp ->
            logDebug "Test HTTP Response - Status: ${resp.status}, Headers: ${resp.headers}"

            if (resp.status >= 200 && resp.status < 300) {
                def successMsg = "Connection test successful! (HTTP ${resp.status})"
                log.info successMsg
                sendEvent(name: "connectionStatus", value: "Connected")
            } else {
                def errorMsg = "Connection test failed - Server returned HTTP ${resp.status}"
                log.warn errorMsg
                sendEvent(name: "connectionStatus", value: "Error")
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        def errorMsg = handleHttpError(e, true)
        log.error errorMsg
        sendEvent(name: "connectionStatus", value: "Error")
    } catch (java.net.ConnectException e) {
        def errorMsg = "Connection test failed - Cannot connect to server '${ntfyHost}'. Check host and network connectivity."
        log.error errorMsg
        sendEvent(name: "connectionStatus", value: "Disconnected")
    } catch (java.net.UnknownHostException e) {
        def errorMsg = "Connection test failed - Unknown host '${ntfyHost}'. Check the hostname."
        log.error errorMsg
        sendEvent(name: "connectionStatus", value: "Disconnected")
    } catch (java.net.SocketTimeoutException e) {
        def errorMsg = "Connection test failed - Connection timeout. Server may be slow or unreachable."
        log.error errorMsg
        sendEvent(name: "connectionStatus", value: "Timeout")
    } catch (Exception e) {
        def errorMsg = "Connection test failed - Unexpected error: ${e.message}"
        log.error errorMsg
        logDebug "Full exception details: ${e}"
        sendEvent(name: "connectionStatus", value: "Error")
    }
}

private Map validateConfiguration() {
    def errors = []

    if (!ntfyHost || ntfyHost.trim().isEmpty()) {
        errors.add("NTFY Host is required")
    }

    if (!ntfyTopic || ntfyTopic.trim().isEmpty()) {
        errors.add("Topic is required")
    }

    if (!ntfyProtocol || !["http", "https"].contains(ntfyProtocol)) {
        errors.add("Protocol must be http or https")
    }

    // Validate topic format (basic validation)
    if (ntfyTopic && !ntfyTopic.matches(/^[a-zA-Z0-9_-]+$/)) {
        errors.add("Topic can only contain letters, numbers, underscores, and hyphens")
    }

    // Validate authentication - if username is provided, password should be too
    if (ntfyUsername && ntfyUsername.trim() && (!ntfyPassword || ntfyPassword.trim().isEmpty())) {
        errors.add("Password is required when username is provided")
    }

    // Validate click action configuration
    if (ntfyClickAction && ntfyClickAction != "none") {
        if (!ntfyActionUrl || ntfyActionUrl.trim().isEmpty()) {
            errors.add("Action URL is required when click action is selected")
        } else if (!ntfyActionUrl.trim().startsWith("http://") && !ntfyActionUrl.trim().startsWith("https://")) {
            errors.add("Action URL must start with http:// or https://")
        }
    }

    // Validate attachment configuration
    if (ntfyAttachUrl && ntfyAttachUrl.trim()) {
        if (!ntfyAttachUrl.trim().startsWith("http://") && !ntfyAttachUrl.trim().startsWith("https://")) {
            errors.add("Attachment URL must start with http:// or https://")
        }
    }

    // Validate icon URL
    if (ntfyIconUrl && ntfyIconUrl.trim()) {
        if (!ntfyIconUrl.trim().startsWith("http://") && !ntfyIconUrl.trim().startsWith("https://")) {
            errors.add("Icon URL must start with http:// or https://")
        }
    }

    // Validate scheduling configuration
    if (ntfyScheduling && ntfyScheduling != "none") {
        if (ntfyScheduling == "delay") {
            if (!ntfyDelayMinutes || ntfyDelayMinutes <= 0) {
                errors.add("Delay minutes must be a positive number when delay scheduling is selected")
            }
        } else if (ntfyScheduling == "at") {
            if (!ntfyScheduleTime || ntfyScheduleTime.trim().isEmpty()) {
                errors.add("Schedule time is required when 'at' scheduling is selected")
            } else if (!ntfyScheduleTime.trim().matches(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)) {
                errors.add("Schedule time must be in format YYYY-MM-DD HH:MM:SS")
            }
        }
    }

    if (errors.isEmpty()) {
        return [valid: true]
    } else {
        return [valid: false, error: errors.join("; ")]
    }
}

private String buildNotificationUri() {
    return "${ntfyProtocol}://${ntfyHost.trim()}/${ntfyTopic.trim()}"
}

private Map buildHeaders() {
    def headers = [:]

    if (ntfyTitle && ntfyTitle.trim()) {
        headers["Title"] = ntfyTitle.trim()
    }

    if (ntfyPriority && ntfyPriority != "null") {
        headers["Priority"] = ntfyPriority
    }

    // Add tags if provided (emojis auto-convert based on tag names)
    if (ntfyTags && ntfyTags.trim()) {
        headers["Tags"] = ntfyTags.trim()
        logDebug "Added tags: ${ntfyTags.trim()}"
    }

    // Add click action if configured
    if (ntfyClickAction && ntfyClickAction != "none" && ntfyActionUrl && ntfyActionUrl.trim()) {
        if (ntfyClickAction == "view") {
            headers["Click"] = ntfyActionUrl.trim()
            logDebug "Added view click action: ${ntfyActionUrl.trim()}"
        } else if (ntfyClickAction == "http") {
            headers["Actions"] = "http, Open, ${ntfyActionUrl.trim()}"
            logDebug "Added HTTP click action: ${ntfyActionUrl.trim()}"
        }
    }

    // Add attachment if provided
    if (ntfyAttachUrl && ntfyAttachUrl.trim()) {
        headers["Attach"] = ntfyAttachUrl.trim()
        logDebug "Added attachment: ${ntfyAttachUrl.trim()}"

        // Add filename if provided
        if (ntfyAttachFilename && ntfyAttachFilename.trim()) {
            headers["Filename"] = ntfyAttachFilename.trim()
            logDebug "Added attachment filename: ${ntfyAttachFilename.trim()}"
        }
    }

    // Add custom icon if provided
    if (ntfyIconUrl && ntfyIconUrl.trim()) {
        headers["Icon"] = ntfyIconUrl.trim()
        logDebug "Added custom icon: ${ntfyIconUrl.trim()}"
    }

    // Add sequence-id if provided
    if (ntfySequenceId && ntfySequenceId.trim()) {
        headers["X-Sequence-Id"] = ntfySequenceId.trim()
        logDebug "Added sequence-id: ${ntfySequenceId.trim()}"
    }

    // Add markdown formatting if enabled
    if (ntfyMarkdown) {
        headers["Markdown"] = "yes"
        logDebug "Enabled markdown formatting"
    }

    // Add scheduling if configured
    if (ntfyScheduling && ntfyScheduling != "none") {
        if (ntfyScheduling == "delay" && ntfyDelayMinutes && ntfyDelayMinutes > 0) {
            headers["Delay"] = "${ntfyDelayMinutes}m"
            logDebug "Added delay scheduling: ${ntfyDelayMinutes} minutes"
        } else if (ntfyScheduling == "at" && ntfyScheduleTime && ntfyScheduleTime.trim()) {
            headers["At"] = ntfyScheduleTime.trim()
            logDebug "Added scheduled time: ${ntfyScheduleTime.trim()}"
        }
    }

    // Add authentication if provided
    if (ntfyUsername && ntfyUsername.trim() && ntfyPassword && ntfyPassword.trim()) {
        def credentials = "${ntfyUsername.trim()}:${ntfyPassword}".bytes.encodeBase64().toString()
        headers["Authorization"] = "Basic ${credentials}"
        logDebug "Added Basic authentication for user: ${ntfyUsername.trim()}"
    }

    return headers
}

private String handleHttpError(groovyx.net.http.HttpResponseException e, boolean isTest = false) {
    def operation = isTest ? "Connection test" : "Notification"

    switch (e.statusCode) {
        case 400:
            return "${operation} failed - Bad request (HTTP 400). Check your message format and topic name."
        case 401:
            return "${operation} failed - Unauthorized (HTTP 401). Check your username and password."
        case 403:
            return "${operation} failed - Forbidden (HTTP 403). You don't have permission to publish to this topic."
        case 404:
            return "${operation} failed - Not found (HTTP 404). Check your server host and topic name."
        case 413:
            return "${operation} failed - Message too large (HTTP 413). Reduce message size."
        case 429:
            return "${operation} failed - Rate limited (HTTP 429). Too many requests, try again later."
        case 500:
            return "${operation} failed - Server error (HTTP 500). The NTFY server is experiencing issues."
        case 502:
        case 503:
        case 504:
            return "${operation} failed - Server unavailable (HTTP ${e.statusCode}). The NTFY server is temporarily unavailable."
        default:
            return "${operation} failed - HTTP ${e.statusCode}: ${e.message}"
    }
}

private void updateNotificationState(String status, String details) {
    def timestamp = new Date().format('yyyy-MM-dd HH:mm:ss')
    sendEvent(name: "lastNotificationStatus", value: status)
    sendEvent(name: "lastNotificationTime", value: timestamp)
    logDebug "Updated notification state - Status: ${status}, Time: ${timestamp}, Details: ${details}"
}

private void logDebug(msg) {
    if (logEnable) log.debug msg
}
