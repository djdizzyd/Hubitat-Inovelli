/**
 *  Copyright 2019 Inovelli / Eric Maycock
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
 *  Inovelli Bulb Multi-White LZW41
 *
 *  Author: Eric Maycock
 *  Date: 2019-9-9
 *  Forked and updated by bcopeland 1/2/2020 
 *    - removed turn on when set color temp
 *  Modified to allow for colorStaging preference per official standards
 */

metadata {
	definition (name: "Inovelli Bulb Multi-White LZW41 DDD", namespace: "djdizzyd", author: "InovelliUSA", importUrl: "https://raw.githubusercontent.com/djdizzyd/Hubitat-Inovelli/master/Drivers/inovelli-bulb-multi-white-lzw41.src/inovelli-bulb-multi-white-lzw41.groovy") {
		capability "Switch Level"
		capability "Color Temperature"
		capability "Switch"
		capability "Refresh"
		capability "Actuator"
		capability "Sensor"
		capability "Health Check"
		capability "Configuration"

		attribute "colorName", "string"
        
        fingerprint mfr: "0300", prod: "0006", model: "0001", deviceJoinName: "Inovelli Bulb Multi-White" //US
        fingerprint deviceId: "0x1101", inClusters: "0x5E,0x85,0x59,0x86,0x72,0x5A,0x26,0x33,0x27,0x70,0x7A,0x73,0x98,0x7A"
        fingerprint deviceId: "0x1101", inClusters: "0x5E,0x98,0x86,0x85,0x59,0x72,0x73,0x26,0x33,0x70,0x27,0x5A,0x7A" // Secure
        fingerprint deviceId: "0x1101", inClusters: "0x5E,0x85,0x59,0x86,0x72,0x5A,0x26,0x33,0x27,0x70,0x73,0x98,0x7A"
	}
	preferences {
		// added for official hubitat standards
		input name: "colorStaging", type: "bool", description: "", title: "Enable color pre-staging", defaultValue: false
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
		input name: "bulbMemory", type: "bool", title: "Enable Power State Memory", defaultValue: false
	}
}

private getCOLOR_TEMP_MIN() { 2700 }
private getCOLOR_TEMP_MAX() { 6500 }
private getWARM_WHITE_CONFIG() { 0x51 }
private getCOLD_WHITE_CONFIG() { 0x52 }
private getWARM_WHITE() { "warmWhite" }
private getCOLD_WHITE() { "coldWhite" }
private getWHITE_NAMES() { [WARM_WHITE, COLD_WHITE] }

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def updated() {
	log.info "updated().."
	log.warn "debug logging is: ${logEnable == true}"
	log.warn "color staging is: ${colorStaging == false}"
	log.warn "bulb memory is: ${bulbMemory == false}"

	if (logEnable) runIn(1800,logsOff)
	response(refresh())
}

def configure() {
	def value = 0
	def cmds = []
	if (bulbMemory) value=1
	cmds << zwave.configurationV1.configurationSet([scaledConfigurationValue: value, parameterNumber: 2, size:1])
	cmds << zwave.configurationV2.configurationGet([parameterNumber: 2])
	commands(cmds)
}

def installed() {
	if (logEnable) log.debug "installed()..."
	sendEvent(name: "checkInterval", value: 1860, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "0"])
	sendEvent(name: "level", value: 100, unit: "%")
	sendEvent(name: "colorTemperature", value: 2700)
}

def parse(description) {
	def result = null
	if (description != "updated") {
        if (logEnable) log.debug("description: $description")
		def cmd = zwave.parse(description,[0x33:1,0x08:2,0x26:3])
		if (cmd) {
			result = zwaveEvent(cmd)
			if(debugLogging) log.debug("'$description' parsed to $result")
		} else {
			log.warn("Couldn't zwave.parse '$description'")
		}
	}
	result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    if (logEnable) log.debug cmd
	dimmerEvents(cmd)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
	dimmerEvents(cmd)
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
    if (logEnable) log.debug cmd
	unschedule(offlinePing)
	dimmerEvents(cmd)
}

def zwaveEvent(hubitat.zwave.commands.switchcolorv1.SwitchColorReport cmd) {
	if (logEnable) log.debug "got SwitchColorReport: $cmd"
	def result = []
	if (cmd.value == 255) {
		def parameterNumber = (cmd.colorComponent == WARM_WHITE) ? WARM_WHITE_CONFIG : COLD_WHITE_CONFIG
		if (logEnable) log.debug "got value 255 from $parameterNumber"
		result << response(command(zwave.configurationV2.configurationGet([parameterNumber: parameterNumber])))
	}
	result
}



private dimmerEvents(hubitat.zwave.Command cmd) {
	def value = (cmd.value ? "on" : "off")
	def result = [createEvent(name: "switch", value: value, descriptionText: "$device.displayName was turned $value")]
	if (cmd.value) {
		result << createEvent(name: "level", value: cmd.value == 99 ? 100 : cmd.value , unit: "%")
	}
	return result
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCommand = cmd.encapsulatedCommand()
	if (encapsulatedCommand) {
		zwaveEvent(encapsulatedCommand)
	} else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
		createEvent(descriptionText: cmd.toString())
	}
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
    //log.debug cmd
    if (logEnable) log.debug "got ConfigurationReport: $cmd"
    def result = null
	if (cmd.parameterNumber == WARM_WHITE_CONFIG || cmd.parameterNumber == COLD_WHITE_CONFIG) {
        result = createEvent(name: "colorTemperature", value: cmd.scaledConfigurationValue)
		setGenericTempName(cmd.scaledConfigurationValue)
    }
	if (cmd.parameterNumber == 0x02) {
		state.powerStateMem = cmd.scaledConfigurationValue
	}
	result    
}

def zwaveEvent(hubitat.zwave.Command cmd) {
	def linkText = device.label ?: device.name
	[linkText: linkText, descriptionText: "$linkText: $cmd", displayed: false]
}

def buildOffOnEvent(cmd){
	[zwave.basicV1.basicSet(value: cmd), zwave.switchMultilevelV3.switchMultilevelGet()]
}

def on() {
	commands(buildOffOnEvent(0xFF), 5000)
}

def off() {
	commands(buildOffOnEvent(0x00), 5000)
}

def refresh() {
    commands([zwave.switchMultilevelV3.switchMultilevelGet()] + queryAllColors(), 500)
}

def ping() {
	if (logEnable) log.debug "ping().."
	unschedule(offlinePing)
	runEvery30Minutes(offlinePing)
	command(zwave.switchMultilevelV3.switchMultilevelGet())
}

def offlinePing() {
	if (logEnable) log.debug "offlinePing()..."
	sendHubCommand(new hubitat.device.HubAction(command(zwave.switchMultilevelV3.switchMultilevelGet())))
}

def setLevel(level) {
	setLevel(level, 1)
}

def setLevel(level, duration) {
	if (logEnable) log.debug "setLevel($level, $duration)"
	if(level > 99) level = 99
	commands([
		zwave.switchMultilevelV3.switchMultilevelSet(value: level, dimmingDuration: duration),
		zwave.switchMultilevelV3.switchMultilevelGet(),
	], (duration && duration < 12) ? (duration * 1000) : 3500)
}

def setColorTemperature(temp) {
	if (logEnable) log.debug "setColorTemperature($temp)"
	def warmValue = temp < 5000 ? 255 : 0
	def coldValue = temp >= 5000 ? 255 : 0
	def parameterNumber = temp < 5000 ? WARM_WHITE_CONFIG : COLD_WHITE_CONFIG
	def cmds = []
    cmds << zwave.switchColorV3.switchColorSet(warmWhite: warmValue, coldWhite: coldValue)
    if (temp < COLOR_TEMP_MIN) temp = 2700
    if (temp > COLOR_TEMP_MAX) temp = 6500
    cmds << zwave.configurationV1.configurationSet([scaledConfigurationValue: temp, parameterNumber: parameterNumber, size:2])
    if ((device.currentValue("switch") != "on") && (!colorStaging)) {
		if (logEnable) log.debug "Bulb is off. Turning on"
		cmds << zwave.basicV1.basicSet(value: 0xFF)
		cmds << zwave.switchMultiLevelV3.switchMultilevelGet()
	}
	commands(cmds + queryAllColors())
}

private queryAllColors() {
	WHITE_NAMES.collect { zwave.switchColorV3.switchColorGet(colorComponent: it) }
	//[zwave.basicV1.basicGet()] /*+ WHITE_NAMES.collect { zwave.switchColorV3.switchColorGet(colorComponentId: it) }*/
}

private secEncap(hubitat.zwave.Command cmd) {
	zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
}

private crcEncap(hubitat.zwave.Command cmd) {
	zwave.crc16EncapV1.crc16Encap().encapsulate(cmd).format()
}

private command(hubitat.zwave.Command cmd) {
	if (getDataValue("zwaveSecurePairingComplete") == "true") {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
		return cmd.format()
    }	
}

private commands(commands, delay=200) {
	delayBetween(commands.collect{ command(it) }, delay)
}


def setGenericTempName(temp){
    if (!temp) return
    def genericName
    def value = temp.toInteger()
    if (value <= 2000) genericName = "Sodium"
    else if (value <= 2100) genericName = "Starlight"
    else if (value < 2400) genericName = "Sunrise"
    else if (value < 2800) genericName = "Incandescent"
    else if (value < 3300) genericName = "Soft White"
    else if (value < 3500) genericName = "Warm White"
    else if (value < 4150) genericName = "Moonlight"
    else if (value <= 5000) genericName = "Horizon"
    else if (value < 5500) genericName = "Daylight"
    else if (value < 6000) genericName = "Electronic"
    else if (value <= 6500) genericName = "Skylight"
    else if (value < 20000) genericName = "Polar"
    def descriptionText = "${device.getDisplayName()} color is ${genericName}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "colorName", value: genericName ,descriptionText: descriptionText)
}

def setDefaultAssociations() {
    def hubitatHubID = zwaveHubNodeId.toString().format( '%02x', zwaveHubNodeId )
    state.defaultG1 = [hubitatHubID]
    state.defaultG2 = []
    state.defaultG3 = []
}

def setAssociationGroup(group, nodes, action, endpoint = null){
    if (!state."desiredAssociation${group}") {
        state."desiredAssociation${group}" = nodes
    } else {
        switch (action) {
            case 0:
                state."desiredAssociation${group}" = state."desiredAssociation${group}" - nodes
            break
            case 1:
                state."desiredAssociation${group}" = state."desiredAssociation${group}" + nodes
            break
        }
    }
}

def processAssociations(){
   def cmds = []
   setDefaultAssociations()
   def associationGroups = 5
   if (logEnable) log.debug state.associationGroups
   if (state.associationGroups) {
       associationGroups = state.associationGroups
   } else {
       if (logEnable) log.debug "Getting supported association groups from device"
       cmds <<  zwave.associationV2.associationGroupingsGet().format()
   }
   for (int i = 1; i <= associationGroups; i++){
      if(state."actualAssociation${i}" != null){
         if(state."desiredAssociation${i}" != null || state."defaultG${i}") {
            def refreshGroup = false
            ((state."desiredAssociation${i}"? state."desiredAssociation${i}" : [] + state."defaultG${i}") - state."actualAssociation${i}").each {
                if (logEnable) log.debug "Adding node $it to group $i"
                cmds << zwave.associationV2.associationSet(groupingIdentifier:i, nodeId:Integer.parseInt(it,16)).format()
                refreshGroup = true
            }
            ((state."actualAssociation${i}" - state."defaultG${i}") - state."desiredAssociation${i}").each {
                if (logEnable) log.debug "Removing node $it from group $i"
                cmds << zwave.associationV2.associationRemove(groupingIdentifier:i, nodeId:Integer.parseInt(it,16)).format()
                refreshGroup = true
            }
            if (refreshGroup == true) cmds << zwave.associationV2.associationGet(groupingIdentifier:i)
            else log.debug "There are no association actions to complete for group $i"
         }
      } else {
         if (logEnable) log.debug "Association info not known for group $i. Requesting info from device."
         cmds << zwave.associationV2.associationGet(groupingIdentifier:i).format()
      }
   }
   return cmds
}
