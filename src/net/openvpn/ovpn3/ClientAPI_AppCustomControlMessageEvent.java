package net.openvpn.ovpn3;

public class ClientAPI_AppCustomControlMessageEvent {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected ClientAPI_AppCustomControlMessageEvent(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(ClientAPI_AppCustomControlMessageEvent obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  public synchronized void delete() {
    if (swigCPtr != 0) {
      if (swigCMemOwn) {
        swigCMemOwn = false;
        ovpncliJNI.delete_ClientAPI_AppCustomControlMessageEvent(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  public ClientAPI_AppCustomControlMessageEvent() {
    this(ovpncliJNI.new_ClientAPI_AppCustomControlMessageEvent(), true);
  }

  public void setProtocol(String value) {
    ovpncliJNI.ClientAPI_AppCustomControlMessageEvent_protocol_set(swigCPtr, this, value);
  }

  public String getProtocol() {
    return ovpncliJNI.ClientAPI_AppCustomControlMessageEvent_protocol_get(swigCPtr, this);
  }

  public void setPayload(String value) {
    ovpncliJNI.ClientAPI_AppCustomControlMessageEvent_payload_set(swigCPtr, this, value);
  }

  public String getPayload() {
    return ovpncliJNI.ClientAPI_AppCustomControlMessageEvent_payload_get(swigCPtr, this);
  }
}
