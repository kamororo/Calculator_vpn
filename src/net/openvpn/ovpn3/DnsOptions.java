package net.openvpn.ovpn3;

/**
 * Hand-written equivalent of the older SWIG-generated
 * DnsOptions class, matching the libovpn3.so shipped
 * in this APK. The newer generated Java (Amnezia)
 * has no DnsOptions at all: newer openvpn3 replaced
 * tun_builder_set_dns_options() with separate
 * add_dns_server/add_search_domain callbacks.
 * The old core in this APK still calls
 * tun_builder_set_dns_options(const DnsOptions&),
 * and its SWIG director constructs a DnsOptions
 * object via the (long, boolean) constructor and
 * passes it to tun_builder_set_dns_options().
 * So this class MUST exist with exactly that
 * constructor and these getters, or the director
 * lookup fails when DNS is delivered.
 */
public class DnsOptions {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected DnsOptions(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(DnsOptions obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  @SuppressWarnings("deprecation")
  protected void finalize() {
    delete();
  }

  public synchronized void delete() {
    if (swigCPtr != 0) {
      if (swigCMemOwn) {
        swigCMemOwn = false;
        ovpncliJNI.delete_DnsOptions(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  public DnsOptions() {
    this(ovpncliJNI.new_DnsOptions(), true);
  }

  public boolean getFrom_dhcp_options() {
    return ovpncliJNI.DnsOptions_from_dhcp_options_get(swigCPtr, this);
  }

  public void setFrom_dhcp_options(boolean value) {
    ovpncliJNI.DnsOptions_from_dhcp_options_set(swigCPtr, this, value);
  }

  public DnsOptions_DomainsList getSearch_domains() {
    return new DnsOptions_DomainsList(ovpncliJNI.DnsOptions_search_domains_get(swigCPtr, this), false);
  }

  public void setSearch_domains(DnsOptions_DomainsList value) {
    ovpncliJNI.DnsOptions_search_domains_set(swigCPtr, this, DnsOptions_DomainsList.getCPtr(value), value);
  }

  public DnsOptions_ServersMap getServers() {
    return new DnsOptions_ServersMap(ovpncliJNI.DnsOptions_servers_get(swigCPtr, this), false);
  }

  public void setServers(DnsOptions_ServersMap value) {
    ovpncliJNI.DnsOptions_servers_set(swigCPtr, this, DnsOptions_ServersMap.getCPtr(value), value);
  }

  public String to_string() {
    return ovpncliJNI.DnsOptions_to_string(swigCPtr, this);
  }

  // ---- vector<string> for search domains ----
  public static class DnsOptions_DomainsList {
    private transient long swigCPtr;
    protected transient boolean swigCMemOwn;
    protected DnsOptions_DomainsList(long cPtr, boolean cMemoryOwn) {
      swigCMemOwn = cMemoryOwn;
      swigCPtr = cPtr;
    }
    protected static long getCPtr(DnsOptions_DomainsList obj) {
      return (obj == null) ? 0 : obj.swigCPtr;
    }
    public synchronized void delete() {
      if (swigCPtr != 0) {
        if (swigCMemOwn) {
          swigCMemOwn = false;
          ovpncliJNI.delete_DnsOptions_DomainsList(swigCPtr);
        }
        swigCPtr = 0;
      }
    }
    public int size() { return ovpncliJNI.DnsOptions_DomainsList_doSize(swigCPtr, this); }
    public String get(int i) { return ovpncliJNI.DnsOptions_DomainsList_doGet(swigCPtr, this, i); }
    public boolean isEmpty() { return ovpncliJNI.DnsOptions_DomainsList_isEmpty(swigCPtr, this); }
  }

  // ---- vector<string> for one server's addresses ----
  public static class DnsOptions_AddressList {
    private transient long swigCPtr;
    protected transient boolean swigCMemOwn;
    protected DnsOptions_AddressList(long cPtr, boolean cMemoryOwn) {
      swigCMemOwn = cMemoryOwn;
      swigCPtr = cPtr;
    }
    protected static long getCPtr(DnsOptions_AddressList obj) {
      return (obj == null) ? 0 : obj.swigCPtr;
    }
    public synchronized void delete() {
      if (swigCPtr != 0) {
        if (swigCMemOwn) {
          swigCMemOwn = false;
          ovpncliJNI.delete_DnsOptions_AddressList(swigCPtr);
        }
        swigCPtr = 0;
      }
    }
    public int size() { return ovpncliJNI.DnsOptions_AddressList_doSize(swigCPtr, this); }
    public String get(int i) { return ovpncliJNI.DnsOptions_AddressList_doGet(swigCPtr, this, i); }
    public boolean isEmpty() { return ovpncliJNI.DnsOptions_AddressList_isEmpty(swigCPtr, this); }
  }

  // ---- map<string, AddressList> for servers ----
  public static class DnsOptions_ServersMap {
    private transient long swigCPtr;
    protected transient boolean swigCMemOwn;
    protected DnsOptions_ServersMap(long cPtr, boolean cMemoryOwn) {
      swigCMemOwn = cMemoryOwn;
      swigCPtr = cPtr;
    }
    protected static long getCPtr(DnsOptions_ServersMap obj) {
      return (obj == null) ? 0 : obj.swigCPtr;
    }
    public synchronized void delete() {
      if (swigCPtr != 0) {
        if (swigCMemOwn) {
          swigCMemOwn = false;
          ovpncliJNI.delete_DnsOptions_ServersMap(swigCPtr);
        }
        swigCPtr = 0;
      }
    }
    public boolean isEmpty() { return ovpncliJNI.DnsOptions_ServersMap_isEmpty(swigCPtr, this); }
    public Iterator begin() {
      return new Iterator(ovpncliJNI.DnsOptions_ServersMap_begin(swigCPtr, this), false);
    }
    public Iterator end() {
      return new Iterator(ovpncliJNI.DnsOptions_ServersMap_end(swigCPtr, this), false);
    }
    public static class Iterator {
      private transient long swigCPtr;
      protected transient boolean swigCMemOwn;
      protected Iterator(long cPtr, boolean cMemoryOwn) {
        swigCMemOwn = cMemoryOwn;
        swigCPtr = cPtr;
      }
      protected static long getCPtr(Iterator obj) {
        return (obj == null) ? 0 : obj.swigCPtr;
      }
      public synchronized void delete() {
        if (swigCPtr != 0) {
          if (swigCMemOwn) {
            swigCMemOwn = false;
            ovpncliJNI.delete_DnsOptions_ServersMap_Iterator(swigCPtr);
          }
          swigCPtr = 0;
        }
      }
      public Iterator getNextUnchecked() {
        return new Iterator(ovpncliJNI.DnsOptions_ServersMap_Iterator_getNextUnchecked(swigCPtr, this), true);
      }
      public boolean isNot(Iterator other) {
        return ovpncliJNI.DnsOptions_ServersMap_Iterator_isNot(swigCPtr, this, getCPtr(other), other);
      }
      public String getKey() {
        return ovpncliJNI.DnsOptions_ServersMap_Iterator_getKey(swigCPtr, this);
      }
      public DnsOptions_AddressList getValue() {
        return new DnsOptions_AddressList(ovpncliJNI.DnsOptions_ServersMap_Iterator_getValue(swigCPtr, this), false);
      }
    }
  }
}
