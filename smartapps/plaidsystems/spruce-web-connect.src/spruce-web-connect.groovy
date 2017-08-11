/**
 *  Spruce Web Connect
 *  v1.01 - 01/19/17 - fix variable initialization for schedules
 *  v1.02 - 01/22/17 - added state var to fix notifications for manual schedule starts that are pushed from the webapp
 *  v1.03 - 02/19/17 - edit finished time wording/remove superfluous 'at'; fixed time display errors when updating schedule times; fix state variable assignment for active schedule for proper api reporting.
 *  v1.04 - 03/15/17 - add support for rain sensor
 *  v1.041- 03/18/17 - bugfix issue from 1.04 where zone states were not sending properly
 *  v1.05 - 04/04/17 - runlist retry if not retrieved. after 2nd attempt, skip watering.
 *  v1.06 - 05/08/17 - add responses for all endpoints. update api endpoints. improve runlist response handling + fix retry. bugfix for saving new schedules to map.
 *	v1.07 - 05/11/17 - convert battery % back to voltage ln 583
 *
 *  Copyright 2017 Plaid Systems
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Spruce Web Connect",
    namespace: "plaidsystems",
    author: "Plaid Systems",
    description: "Connect Spruce devices to the Spruce Cloud",
    category: "",
    iconUrl: "http://www.plaidsystems.com/smartthings/st_spruce_leaf_250f.png",
    iconX2Url: "http://www.plaidsystems.com/smartthings/st_spruce_leaf_250f.png",
    iconX3Url: "http://www.plaidsystems.com/smartthings/st_spruce_leaf_250f.png",
    oauth: true)
{
	appSetting "clientId"
	appSetting "clientSecret"
    appSetting "serverUrl"
}

preferences (oauthPage: "authPage") {
	page(name: "authPage")
 	page(name: "pageConnect")
    page(name: "pageDevices")
    page(name: "pageUnsetKey")
}

def authPage() {
    if(!state.accessToken) createAccessToken()
    
    if(!state.key)    {
		pageConnect()
    }
    else {
    	pageDevices()
    }
}

def pageConnect() {
    if(!state.key)    {
		def spruce_oauth_url = "https://app.spruceirrigation.com/connect-smartthings?access_token=${state.accessToken}&logout"
        dynamicPage(name: "pageConnect", title: "Connect to Spruce",  uninstall: false, install:false) { 
            section {
                href url:"https://app.spruceirrigation.com/register?gateway=smartthings", style:"embedded", required:false, title:"Register", image: 'http://www.plaidsystems.com/smartthings/st_spruce_leaf_250f.png', description: "Spruce account is required."
            } 
            section {
                href url: spruce_oauth_url, style:"embedded", required:false, title:"Login to Spruce Cloud", image: 'http://www.plaidsystems.com/smartthings/st_spruce_leaf_250f.png', description: "Login to grant access"
            }
        }
    }
    else {
    	pageDevices()
    }
}

def pageDevices() {
    if(!state.key)    {
		pageConnect()
    }
    else {
    	dynamicPage(name: "pageDevices", uninstall: true, install:true) { 
            section("Select Spruce Controllers to connect:") {
                input "switches", "capability.switch", title: "Spruce Irrigation Controller:", required: false, multiple: true 
            }
            section("Select Spruce Sensors to connect:") {
                input "sensors", "capability.relativeHumidityMeasurement", title: "Spruce Moisture sensors:", required: false, multiple: true 
            }
            section ("Tap Done to save changes to Spruce Cloud"){
                href url:"https://app.spruceirrigation.com/devices", style:"external", required:false, title:"Spruce WebApp", image: 'http://www.plaidsystems.com/smartthings/st_spruce_leaf_250f.png', description: "Click to visit app.spruceirrigation.com"
            }      
            section {
                href page: "pageUnsetKey", title:"Reset Login", description: "Tap to forget Spruce API key and re-start login. For troubleshooting only."
            }          
        }   
    }
}

def pageUnsetKey() {
	state.key = null
    dynamicPage(name: "pageUnsetKey", uninstall: false, install:false) { 
        section {
            paragraph "Spruce API key forgotten. Go back to re-start connect process."  
        }          
    }   
}

def oauthInitUrl() /*not used*/
{
    log.debug "oauthInitUrl"    
    def oauthClientId = appSettings.clientId
	def oauthClientSecret = appSettings.clientSecret
	def oauth_url = "https://app.spruceirrigation.com/connect-smartthings?client=${oauthClientId}&secret=${oauthClientSecret}"
    
    log.debug(oauthClientId)
    log.debug(oauthClientSecret)
 	
	return oauth_url	
}

mappings {
  path("/schedule") {
    action: [
      GET: "listSwitches"
    ]
  }
  path("/schedule/:command") {
    action: [
      POST: "setSchedule"
    ]
  }
  path("/delete/:command") {
    action: [
      POST: "deleteSchedule"
    ]
  }
  path("/zonetime/:command") {
    action: [
      PUT: "zonetimes"
    ]
  }
  path("/zoneoption/:command") {
    action: [
      PUT: "zoneoptions"
    ]
  }
  path("/run/:command") {
    action: [
      POST: "runZone",
      GET: "runZone"
    ]
  }
  path("/apikey/:command") {
    action: [
      POST: "setKey"
    ]
  }
  path("/delay/:command") {
    action: [
      POST: "setDelay"
    ]
  }
}

//***************** install *******************************

def installed() {
	log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"	
	unsubscribe()
    initialize()    
}

def initialize() {	
    log.debug "initialize"  
    
    state.run_today = false
    state.delay = '0'
    if (settings.switches) getSwitches()
    if (settings.sensors) getSensors()
    
    //add devices to web, check for schedules
    if(state.key){
    	addDevices()
    	addSchedules()
	}
}

//set spruce api key
def setKey(){
	log.debug "setkey: " + params.command
    
	state.key = params.command    
    if (state.key && state.key == params.command) {
    	log.debug "API key set, get schedules"
        //getSchedule()
        return [error: false, return_value: 1, data: 'key set']
        }
    else return [error: true, return_value: 0, data: 'key not set']
    //else return httpError(400, "$command is not a valid command for all switches specified")
       
}

//set pump delay
def setDelay(){
	//I'm sending deviceid and delay - still need to update to parse the 2 
    //   /delay/deviceid=' + deviceID + '&delay=' + value;
	log.debug "setdelay: " + params.command
    
    def p = params.command.split('&')
    def delay = p[1].split('=')[1]  
	
    state.delay = delay 
    if (delay == 'delete') state.delay = '0'
     
    if (state.delay == delay) {
    	log.debug "Delay set = " + delay
        return [error: false, return_value: 1, data: 'delay set']
    }
    else if ( delay == 'delete' && state.delay == '0') {
    	log.debug "Delay deleted "
        return [error: false, return_value: 1, data: 'delay deleted']
    }
    else return [error: true, return_value: 0, data: 'delay not set']
    //else return httpError(400, "$command is not a valid command for all switches specified")
       
}

//switch subscriptions
def getSwitches(){
	log.debug "getSwitches: " + settings.switches    
    
    state.switches = [:]
    settings.switches.each{
    	state.switches[it]= (it.device.zigbeeId)
        }
    
    subscribe(settings.switches, "switch", switchHandler)
    subscribe(settings.switches, "switch1", switchHandler)
    subscribe(settings.switches, "switch2", switchHandler)
    subscribe(settings.switches, "switch3", switchHandler)
    subscribe(settings.switches, "switch4", switchHandler)
    subscribe(settings.switches, "switch5", switchHandler)
    subscribe(settings.switches, "switch6", switchHandler)
    subscribe(settings.switches, "switch7", switchHandler)
    subscribe(settings.switches, "switch8", switchHandler)
    subscribe(settings.switches, "switch9", switchHandler)
    subscribe(settings.switches, "switch10", switchHandler)
    subscribe(settings.switches, "switch11", switchHandler)
    subscribe(settings.switches, "switch12", switchHandler)
    subscribe(settings.switches, "switch13", switchHandler)
    subscribe(settings.switches, "switch14", switchHandler)
    subscribe(settings.switches, "switch15", switchHandler)
    subscribe(settings.switches, "switch16", switchHandler)
    subscribe(settings.switches, "rainsensor", switchHandler)
    
}

//sensor subscriptions
def getSensors(){    
    log.debug "getSensors: " + settings.sensors    
    
    state.sensors = [:]    
    settings.sensors.each{
    	state.sensors[it]= (it.device.zigbeeId)
        }
    
    subscribe(settings.sensors, "humidity", sensorHandler)
    subscribe(settings.sensors, "temperature", sensorHandler)
    subscribe(settings.sensors, "battery", sensorHandler)
    
}

//add devices to web
def addDevices(){
	
    //add controllers to web
    state.switches.each{
    	def PUTparams = [
            uri: "https://api.spruceirrigation.com/v1/device/" + it.value,
            headers: [ 	"Authorization": state.key],        
            body: [
                nickname: it.key,
                type: "CO",
                gateway: "smartthings",
                num_zones: "16"
                ]
        ]    
        log.debug PUTparams
        try{
            httpPut(PUTparams){
                resp -> //resp.data {
                    log.debug "${resp.data}"               
                }                
            } 
        catch (e) {
            log.debug "send DB error: $e"
            }
    }
	
    //add sensors to web	
    state.sensors.each{
    	def PUTparams = [
            uri: "https://api.spruceirrigation.com/v1/device/" + it.value,
            headers: [ 	"Authorization": state.key],        
            body: [
                nickname: it.key,
                type: "MS",
                gateway: "smartthings"
                ]
        ]    
        try{
            httpPut(PUTparams){
                resp -> //resp.data {
                    log.debug "${resp.data}"               
                }                
            } 
        catch (e) {
            log.debug "send DB error: $e"
            }
            }


}

//***************** schedule setup commands ******************************
//check for pre-set schedules
def addSchedules(){
	def respMessage = ""
    def key = state.key
    def switchID
    
    state.switches.each{        
        switchID = it.value
        respMessage = "$switchID: "
        
        def newuri =  "https://api.spruceirrigation.com/v1/settings?deviceid="
        newuri += switchID       

        def scheduleType
        def scheduleID = []    
        state.scheduleMap = [:]
        state.manualMap = [:]

        def GETparams = [
                uri: newuri,		
                headers: [ 	"Authorization": key],           
                ]

        try{ httpGet(GETparams) { resp ->	
            //get schedule list        
            scheduleID = resp.data['controller'][switchID]['schedules'].split(',')
            if (scheduleID) {
                //get schedule types
                def i = 1
                scheduleID.each{
                    if ( resp.data['schedule'][it]['sched_enabled'] == '1') {
                        scheduleType = resp.data['schedule'][it]['schedule_type']
                        if (scheduleType == 'connected' || scheduleType == 'basic'){                    	
                            //state.scheduleMap[it] = ['id': i, 'deviceid' : switchID, 'start_time' : resp.data['schedule'][it]['start_time']]
                            state.scheduleMap[i] = ['scheduleid' : it, 'deviceid' : switchID, 'start_time' : resp.data['schedule'][it]['start_time'], 'name' : resp.data['schedule'][it]['schedule_name']]
                            }                     
                            i++                        
                    }
                    if (resp.data['schedule'][it]['sched_enabled'] == '1' && resp.data['schedule'][it]['schedule_type'] == 'manual') {
                            respMessage += " Manual Sch acquired, "
                            state.manualMap = ['scheduleid' : it, 'deviceid' : switchID, 'start_time' : 'manual', 'run_list' : resp.data['schedule'][it]['run_list'][switchID]['1'], 'name' : resp.data['schedule'][it]['schedule_name']]                            
                    }
                }
                respMessage += "Schedules acquired"
                }
             else respMessage += "No schedules available for controller"   
            }
        }
        catch (e) {
        	respMessage += "No schedules set, API error: $e"
            
            }
        log.debug respMessage        
    }   
    
    if (state.scheduleMap) setScheduleTimes()    
}

//set schedules times to run
def setScheduleTimes(){
	unschedule()
    //log.debug "setting schedule times"
    def message = "";    
    def ii = 0;
    state.scheduleMap.each{ 
    	ii++
        def i = it.key
        def scheduleTime = state.scheduleMap[it.key]['start_time']        
        
        def hms = scheduleTime.split(':')  

        int hh = hms[0].toInteger()
        int mm = hms[1].toInteger()
        int ss = 0

        //set schedule run times            
        schedule("${ss} ${mm} ${hh} ? * *", "Check${i}")
        //log.debug "set schedule Check${i} to ${hh}:${mm}"
        //message += " set to ${hh}:${mm}, "
        if (ii != 1) { message += ", "}
        message += state.scheduleMap[it.key]['name'] + " set to ${scheduleTime}"
       
	}
    note("schedule", message, "d")
}

//command to recieve schedules
def setSchedule() {  

    log.debug(params.command)
    def sch = params.command.split('&')
    def sch_id = sch[0].split('=')[1]    
    
    boolean Sch_Set
    def count = 0
    if (sch[4].split('=')[1] == 'manual'){    	
    	state.manualMap = ['scheduleid' : sch[0].split('=')[1], 'start_time' : sch[1].split('=')[1], 'name' : sch[2].split('=')[1], 'type' : sch[4].split('=')[1]]        
        Sch_Set = true
       }
    else if (state.scheduleMap){
    	state.scheduleMap.each{        	
       		//log.debug "key $it.key, count $count"
            //if (count == it.key.toInteger()) count++            
            count++            
            if (state.scheduleMap[it.key]['scheduleid'] == sch[0].split('=')[1]  && !Sch_Set){
            	
                state.scheduleMap[it.key]['start_time'] = sch[1].split('=')[1]
                state.scheduleMap[it.key]['name'] = sch[2].split('=')[1]
                state.scheduleMap[it.key]['type'] = sch[4].split('=')[1]
            //add deviceid    
                log.debug "Schedule updated"
                Sch_Set = true                
			}
    	}
    }
    else {
    	state.scheduleMap = [:]
        state.scheduleMap[1] = ['scheduleid' : sch[0].split('=')[1], 'start_time' : sch[1].split('=')[1], 'name' : sch[2].split('=')[1], 'type' : sch[4].split('=')[1]]		
        Sch_Set = true
		log.debug "Schedule created"
    }
    if (!Sch_Set && count <= 6){
    	for ( count = 1; count <= 6; count++){
        	//log.debug state.scheduleMap[count.toString()]
            if (state.scheduleMap[count.toString()] == null && !Sch_Set) {
            	state.scheduleMap[count.toString()] = ['scheduleid' : sch[0].split('=')[1], 'start_time' : sch[1].split('=')[1], 'name' : sch[2].split('=')[1], 'type' : sch[4].split('=')[1]]        
            	Sch_Set = true
            	log.debug "Schedule added at $count"                
                }
            }
        }
   	
    if (Sch_Set){
    	
        setScheduleTimes()    
        return [error: false, return_value: 1]
		//return httpError(200, "schedule set")
        }
    else return [error: true, return_value: 0, message: "schedule declined, count exceeded 6"] //return httpError(200, "schedule declined, count exceeded 6") 
    
}

//remove schedule
def deleteSchedule() {  

    log.debug(params.command)
    def sch = params.command.split('&')
    def sch_id = sch[0].split('=')[1]
	log.debug(sch_id)
    def message = "";
    def count = 0
    boolean remove = false
        
    if (state.scheduleMap){
    	state.scheduleMap.each{        	
       		if (state.scheduleMap[it.key]['scheduleid'] == sch_id){            	           
            	count = it.key
                remove = true
                message += state.scheduleMap[it.key]['name']
                message += " removed"
                log.debug "Schedule removed"
                return
                }
    	}
    }
    log.debug count
    if (remove) {
    	state.scheduleMap.remove(count)        
        setScheduleTimes()
        note("schedule", message, "d")
        return [error: false, return_value: 1]
	}
    else return [error: false, return_value: 1, message: "schedule not found, nothing to delete"]
    
}  

//***************** event handlers *******************************
def parse(description) {
	log.debug(description)
}

//controller evts
def switchHandler(evt) {    
    log.debug "switchHandler: ${evt.device}, ${evt.name}, ${evt.value}"
    def scheduleMap
    def scheduleid = 0
    def duration = 0
       
    //post zone on/off to web
    if (evt.value.contains('on') || evt.value.contains('off')){
        
        def device = state.switches["${evt.device}"]   
        def zonestate = 1
        if (evt.value.contains('off')) zonestate = 0    
        
        def EP = 0
        if (evt.name == "rainsensor") {
        	EP = 101
        }        
        else if (evt.name != "switch") {
        	EP = evt.name.replace('switch',"").toInteger()
        }
        
        
        if (state.active_sch != "none" && state.run_today){
            if (state.scheduleMap[state.active_sch]) scheduleMap = state.scheduleMap[state.active_sch]
            else if (state.active_sch == 'manual') scheduleMap = state.manualMap
            else if (state.active_sch == 'cloudSch') scheduleMap = state.cloudSchMap
                        
            if (EP == 0 || state.run_today) {
            //else if (state.run_today) scheduleid = scheduleMap['scheduleid']
            	scheduleid = scheduleMap['scheduleid']
            	duration = scheduleMap['run_length']
            }
        
        }
            
        if (zonestate == 0 && EP != 0) duration = 0
        else if (!state.run_today) duration = settings.switches.currentValue('minutes')[0].toInteger() * 60     
        else if (EP != 0) duration = state.timeMap[(EP+1).toString()].toInteger() * 60        
       
        log.debug "Zone ${EP} ${zonestate} for ${duration}"

        def postTime = now() / 1000

        def POSTparams = [
                        uri: "https://api.spruceirrigation.com/controller/zone",
                        headers: [ 	"Authorization": state.key], 
                        body: [
                            zid: device,                        
                            zone: EP,
                            zonestate: zonestate,
                            zonetime: postTime.toInteger(),
                            duration: duration,
                            schedule_id: scheduleid
                        ]
                    ]
        sendPost(POSTparams)
    }
    
    if (state.run_today && evt.value == 'off') cycleOff()
    else if (!state.run_today && evt.value == 'programOn') manual_schedule()
}

//sensor evts
def sensorHandler(evt) {
    log.debug "sensorHandler: ${evt.device}, ${evt.name}, ${evt.value}"
    
    def device = state.sensors["${evt.device}"]
    
    def uri = "https://api.spruceirrigation.com/controller/"
    if (evt.name == "humidity") uri += "moisture"    
    else uri += evt.name
    
    def value = evt.value
    //added for battery
    if (evt.name == "battery") value = evt.value.toInteger() * 5 + 2500
    
    def POSTparams = [
                    uri: uri,
                    headers: [ 	"Authorization": state.key], 
                    body: [
                        deviceid: device,
                        value: value                        
                    ]
                ]

	sendPost(POSTparams)
}

def sendPost(POSTparams) {
	try{
        httpPost(POSTparams) { 
        	//log.debug 'Data pushed to DB' 
        }
    } 
    catch (e) {
        log.debug 'send DB error: $e'
    }
}

//***************** schedule run commands ******************************
//schedule on
def schOn(){	
    log.debug "run today: ${state.run_today}"
    def sch = state.active_sch
    if(state.run_today){
        //settings.switches.zon()
        settings.switches.start()
        
        def schedule_map
        if (state.scheduleMap[sch]) schedule_map = state.scheduleMap[sch]
    	else if (sch == 'manual' && state.manualMap) schedule_map = state.manualMap
        else if (sch == 'cloudSch' && state.cloudSchMap) schedule_map = state.cloudSchMap
        //else cycleOff()

        def sch_name = schedule_map['name']
        def run_time = schedule_map['run_length']
        run_time = (run_time / 60).toDouble().round(1)
        String finishTime = new Date(now() + (schedule_map['run_length'] * 1000).toLong()).format('h:mm a', location.timeZone)
        note("active", "${sch_name} ends at ${finishTime}", "d")
    }
    else {
    	settings.switches.programOff()
    	//note("skip", "Skip Schedule", "d")
    }
}

//schedule finish/off notification
def cycleOff(){
	log.debug "schedule finished"
    def sch = state.active_sch
    def schedule_map
    if (state.scheduleMap[sch]) schedule_map = state.scheduleMap[sch]
    else if (sch == 'manual' && state.manualMap) schedule_map = state.manualMap
    else if (sch == 'cloudSch' && state.cloudSchMap) schedule_map = state.cloudSchMap
    def sch_name = schedule_map['name']
    
    state.run_today = false
    state.active_sch = "none"
    //settings.switches.off()
    String finishTime = new Date(now().toLong()).format('EE @ h:mm a', location.timeZone)
    note('finished', "${sch_name} finished ${finishTime}", 'd')    
}

//retrieve current runlist
def getTodaysTimes(sch){
    log.debug "get todays times for Check$sch"
    state.run_today = false
    
    def respMessage = ""
    def result = []
    state.cloudSchMap = [:]
        
    def schedule_map
    def scheduleID
    
    if (state.scheduleMap[sch]) schedule_map = state.scheduleMap[sch]
    else if (sch == 'manual' && state.manualMap) schedule_map = state.manualMap
    
    //create map for schedules sent directly from cloud
    if (!schedule_map && sch != 'manual'){
    	scheduleID = sch
        sch = 'cloudSch'
        schedule_map = state.cloudSchMap
        }
    else if (!schedule_map && sch == 'manual'){
    	//no manual schedule set - exit
        result[0] = "skip"
        result[1] = 'No manual schedule set'
    	return result        
    	}
    else scheduleID = schedule_map['scheduleid']    
    
    def switchID = state.switches["${settings.switches[0]}"]
    
    state.active_sch = sch    
    //state.run_today = true	//leave false
    //def update = true
    
    def schedule_name
    def run_length
    def scheduleTime
    def scheduleZone = '10:' + state.delay + ','
    def status_message
    def weather_message    
    
    def error
    def message
           
    def newuri =  "https://api.spruceirrigation.com/schedule/runlist?deviceid="
        newuri += switchID
        newuri += "&scheduleid="
        newuri += scheduleID
        newuri += "&run=true"
        
	//log.debug newuri
    
    def GETparams = [
        uri: newuri,		
        headers: [ 	"Authorization": state.key],           
    ]
    try{ httpGet(GETparams) { resp ->	
        //scheduleid
        schedule_name = resp.data['schedule_name']
        state.run_today = resp.data['run_today']
        scheduleTime = resp.data['start_time']
        scheduleZone += resp.data['runlist']
		run_length = resp.data['run_length']
        status_message = resp.data['status_message']
        weather_message = resp.data['rain_message']
            
        error = resp.data['error']        
        if (error == true) respMessage += resp.data['message']        
        
    	}
	}
    catch (e) {        
        log.debug "send DB error: $e"
        //update = false
        error = true
        
        if (state.retry == false){
    		state.retry = true
        
    		if (sch == '1') runIn(300, Check1, [overwrite: false])
        	else if (sch == '2') runIn(300, Check2, [overwrite: false]) 
        	else if (sch == '3') runIn(300, Check3, [overwrite: false]) 
        	else if (sch == '4') runIn(300, Check4, [overwrite: false]) 
        	else if (sch == '5') runIn(300, Check5, [overwrite: false]) 
        	else if (sch == '6') runIn(300, Check6, [overwrite: false]) 
    		respMessage += "runlist error, retry in 5 minutes, $e"
        }
        else {
            state.retry = false
            respMessage += "runlist retry failed, skipping schedule, $e"
        }
        //result[1] = respMessage
    	//return result
    }

    //log.debug scheduleZone
    
    if (error == false){
    	state.retry = false
        
        respMessage += schedule_name
    	result[0] = weather_message
    
        if (state.run_today){
    	result[0] = "active"
    	respMessage += " starts in 1 min\n "
        }
        else {
            //result[0] = "skip"
            respMessage += " skipping today "
        }        
        
        //save schedule time & zone settings
        if (sch == 'manual'){
        	state.manualMap['run_list'] = scheduleZone
            state.manualMap.put ('run_length', run_length)
            }
        else if (sch == 'cloudSch'){
        	state.cloudSchMap = ['scheduleid' : scheduleID, 'run_list' : scheduleZone, 'name' : schedule_name, 'run_length' : run_length] 
        	}
        else {
        	schedule_map.put ('name', schedule_name)
            state.scheduleMap[sch]['start_time'] = scheduleTime
            
            //only update if run today
            if (state.run_today){
            	state.scheduleMap[sch].put ('run_list', scheduleZone)
            	state.scheduleMap[sch].put ('run_length', run_length)                
			}

            def hms = scheduleTime.split(':')    

            int whh = 23
            int wmm = 0
            int wtime = 0

            int hh = hms[0].toInteger()
            int mm = hms[1].toInteger()
            int ss = 0

            if ( (hh*60 + mm) <= (whh * 60 + wmm) ){
                wtime = hh*60 + mm - 5
                whh = wtime / 60
                wmm = ((wtime / 60 * 100) - (whh * 100)) * 60 /100
                //log.debug "set schedule to ${hh}:${mm}"
            }
            //set schedule run time
            schedule("${ss} ${mm} ${hh} ? * *", "Check${sch}")
		}        
        respMessage += status_message
    }
    else if (sch == 'manual') respMessage += "schedule error, check settings"
    
    log.debug "runlist response $error : $respMessage"   
    result[1] = respMessage
    return result    
}

//parse zone times/cycles from runlist
def zoneCycles(sch) {    
    int i = 0
    
    if (state.run_today){
        def schedule_map
        if (state.scheduleMap[sch]) schedule_map = state.scheduleMap[sch]
    	else if (sch == 'manual' && state.manualMap) schedule_map = state.manualMap
        else if (sch == 'cloudSch' && state.cloudSchMap) schedule_map = state.cloudSchMap
        else state.run_today = false
        
        def scheduleID = schedule_map['scheduleid']    
        def zoneArray = schedule_map['run_list']
        log.debug zoneArray

        def newMap = zoneArray.split(',')

        state.cycleMap = [:]
        state.timeMap = [:]
        def option = []
    //need to add additional settings? added above at scheduleZone = '10:1,'  
        for(i = 0; i < 17; i++){    	
            option = newMap[i].toString().split(':')
            if (i == 0) state.timeMap."${i+1}" = option[0]
            else if (option[1] != "0") state.timeMap."${i+1}" = (Math.round(option[0].toInteger() / option[1].toInteger())).toString()
            else state.timeMap."${i+1}" = option[0]
            state.cycleMap."${i+1}" = option[1]
            }
        //log.debug "cycleMap: ${state.cycleMap}"
        //log.debug "timeMap: ${state.timeMap}"

        //send settings to controller
        settings.switches.settingsMap(state.cycleMap, 4001)
        runIn(20, sendTimeMap)
    }
}

//send runlist times to spruce controller
def sendTimeMap(){
	settings.switches.settingsMap(state.timeMap, 4002)
    }

//**************** scheduled times ********************
def Check1(){	
    runIn(30, zoneCycles1)
    runIn(60, schOn)
    settings.switches.programWait()
    def result = getTodaysTimes('1')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
	log.debug "Starting Check 1 in 1 minute"    
}
def zoneCycles1() {zoneCycles('1')}

def Check2(){
	runIn(30, zoneCycles2)
    runIn(60, schOn)
    settings.switches.programWait()
    def result = getTodaysTimes('2')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
    log.debug "Starting Check 2 in 1 minute"  
}
def zoneCycles2() {zoneCycles('2')}

def Check3(){
	runIn(30, zoneCycles3)
    runIn(60, schOn)
    settings.switches.programWait()
    def result = getTodaysTimes('3')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
    log.debug "Starting Check 3 in 1 minute"  
}
def zoneCycles3() {zoneCycles('3')}

def Check4(){
	runIn(30, zoneCycles4)
    runIn(60, schOn)
    settings.switches.programWait()
    def result = getTodaysTimes('4')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
    log.debug "Starting Check 4 in 1 minute"  
}
def zoneCycles4() {zoneCycles('4')}

def Check5(){
	runIn(30, zoneCycles5)
    runIn(60, schOn)
    settings.switches.programWait()
	def result = getTodaysTimes('5')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
    log.debug "Starting Check 5 in 1 minute"  
}
def zoneCycles5() {zoneCycles('5')}

def Check6(){
	runIn(30, zoneCycles6)
    runIn(60, schOn)
    settings.switches.programWait()
    def result = getTodaysTimes('6')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
    log.debug "Starting Check 6 in 1 minute"  
}
def zoneCycles6() {zoneCycles('6')}

def manual_schedule(){
	//log.debug "Manual Schedule starting"
    runIn(30, zoneCyclesM)
    runIn(60, schOn)
    
    def result = getTodaysTimes('manual')
	def status = result[0]
    def message = result[1]
    note(status, message, "d")
    settings.switches.programWait()
    
    log.debug "Starting Check M in 1 minute"
}
def zoneCyclesM() {zoneCycles('manual')}

def zoneCyclesC() {zoneCycles('cloudSch')}


//************* notifications to device, pushed if requested ******************
def note(status, message, type){
	log.debug "${status}:  ${message}"
    settings.switches.notify("${status}", "${message}")
    if(notify)
    {
      if (notify.contains('Daily') && type == "d"){       
        sendPush "${message}"
      }
      if (notify.contains('Weather') && type == "f"){     
        sendPush "${message}"
      }
      if (notify.contains('Warnings') && type == "w"){     
        sendPush "${message}"
      }
      if (notify.contains('Moisture') && type == "m"){        
        sendPush "${message}"
      }      
    }
}

//**************************** device commands **************************************
def runZone(){
	log.debug(params.command)
    // use the built-in request object to get the command parameter
    def command = params.command
    def zoneonoff = command.split(',')
    
    switch(zoneonoff[0]) {
        case "zon":
            //set turn on time
            def runT = zoneonoff[2].toInteger() / 60
    		settings.switches.manualTime(runT)
         	//pumpOn()
            zoneOn(zoneonoff[1])            
            return [error: false, return_value: 1]
            //def response = 'callback({"error":false,"return_value":1})'
            //render contentType: "application/jsonp", data: response, status: 200
            break
        case "zoff":
            zoneOff(zoneonoff[1])           
            //def response = 'callback({"error":false,"return_value":1})'
            //render contentType: "application/jsonp", data: response, status: 200
            return [error: false, return_value: 1]
            break
        case "son":        	
            //use manual table to store run values
            runIn(30, zoneCyclesC)
            runIn(60, schOn)
            
            //send scheduleID
            def result = getTodaysTimes(zoneonoff[1])
            def status = result[0]
            def message = result[1]

            log.debug "$status, $message"
            note(status, message, "d")
            settings.switches.programWait()

            log.debug "Starting Check cloudSch in 1 minute"
            return [error: false, return_value: 1]
            break
        case "soff": 
        	settings.switches.off()
         	cycleOff()
            return [error: false, return_value: 1]
            break
        default:
            return [error: true, return_value: 0, message: "no command found"] //httpError(400, "$command is not a valid command for all switches specified")
    }    
}

def zoneOn(zone){	
	//settings.switches."z${zone}on"()
    settings.switches.zoneon("${settings.switches[0].deviceNetworkId}.${zone}")
}

def zoneOff(zone){
	//settings.switches."z${zone}off"()
    settings.switches.zoneoff("${settings.switches[0].deviceNetworkId}.${zone}")
}