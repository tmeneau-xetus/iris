package com.xetus.oss.iris.model

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

@ToString
@EqualsAndHashCode
@CompileStatic
class RPCMessage {

  Integer code
  String message
  String name
  String type

}
