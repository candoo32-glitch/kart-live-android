package com.kartgarden.live;

import com.kartgarden.live.protocol.*;
import org.json.JSONObject;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** One subscription per enrolled key, with isolated generations on reconnect. */
public final class KartSession implements AutoCloseable {
    private final ScheduledExecutorService apiWorker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService motorWorker=Executors.newSingleThreadScheduledExecutor();
    public final VideoDecoder decoder=new VideoDecoder();
    public final CsvRecorder recorder;
    private volatile Connection connection;
    private volatile boolean foreground;
    public KartSession(java.io.File directory) { recorder=new CsvRecorder(directory); }
    public void foreground(boolean active) { foreground=active;if(!active)stop("App left foreground"); }
    public Connection current() { return connection; }
    public void connect(ClientConfig config,String host,String profile,CredentialVault vault) {
        disconnect();
        Connection c=new Connection(config,host,profile);connection=c;decoder.reset();
        apiWorker.execute(()->{try{if(!c.alive)return;c.api=new GatewayApi(config,host,vault.tls());c.address=InetAddress.getByName(host);if(!(c.address instanceof Inet4Address))throw new IllegalArgumentException("Use the gateway's IPv4 address");c.video=socket(41000);c.telemetry=socket(41001);if(!c.alive){c.closeSockets();return;}receive(c,c.video,true);receive(c,c.telemetry,false);c.poll=apiWorker.scheduleWithFixedDelay(()->poll(c),0,1,TimeUnit.SECONDS);}catch(Exception e){c.error="Connect failed: "+message(e);c.alive=false;c.closeSockets();}});
    }
    private DatagramSocket socket(int port) throws Exception { DatagramSocket s=new DatagramSocket(null);try{s.setReceiveBufferSize(2*1024*1024);s.bind(new InetSocketAddress(port));s.setSoTimeout(100);return s;}catch(Exception e){s.close();throw e;} }
    private void receive(Connection c,DatagramSocket socket,boolean video) {
        Thread thread=new Thread(()->{
            byte[] buffer=new byte[65535];LspAssembler assembler=new LspAssembler();long generation=0,streamGeneration=-1;
            while(c.alive&&!socket.isClosed())try {
                DatagramPacket packet=new DatagramPacket(buffer,buffer.length);socket.receive(packet);
                synchronized(c.streamLock) {
                if(!c.alive||c!=connection)continue;
                if(streamGeneration!=c.streamGeneration){assembler.reset();streamGeneration=c.streamGeneration;}
                int source=video?c.videoSource:c.telemetrySource;
                if(source<0||packet.getPort()!=source||!packet.getAddress().equals(c.address))continue;
                byte[] data=Arrays.copyOf(packet.getData(),packet.getLength());
                if(video) { c.videoPackets++;c.videoBytes+=data.length;c.lastVideo=System.nanoTime();LspAssembler.Frame frame=assembler.feed(data);if(assembler.lossGeneration!=generation){generation=assembler.lossGeneration;synchronized(decoder){if(c==connection&&c.alive)decoder.loss();}}if(frame!=null&&!frame.damaged())synchronized(decoder){if(c==connection&&c.alive)decoder.feed(frame.annexB());}c.frames=assembler.frames;c.gaps=assembler.gaps; }
                else { Telemetry t=Telemetry.parse(data);c.telemetryPackets++;if(!t.valid)continue;
                    if(t.type==1){c.battery=t.battery;c.cable=t.cable;}
                    if(t.type==2){c.lastImu=System.nanoTime();c.latest=t;synchronized(c.orientation){c.orientation.update(t,c.lastImu);}}
                    recorder.append(t,data);
                }
                }
            }catch(SocketTimeoutException e){if(video)synchronized(decoder){if(c==connection&&c.alive)decoder.drain();}}catch(Exception e){if(c.alive){c.error="UDP receive failed";stopConnection(c,"UDP failure");}break;}
        },video?"kart-video":"kart-telemetry");thread.setDaemon(true);thread.start();
    }
    private void poll(Connection c) {
        if(!c.alive)return;
        try {
            if(c.lastSubscribe==0||System.nanoTime()-c.lastSubscribe>10_000_000_000L) {
                JSONObject ports=new JSONObject().put("video",41000).put("telemetry",41001);
                JSONObject body=new JSONObject().put("ports",ports);if(!c.profile.isEmpty())body.put("profile",c.profile);
                JSONObject response=c.api.request("/v1/subscribe",body);
                if(!c.alive)return;
                JSONObject identity=response.optJSONObject("kart"),map=response.getJSONObject("map"),udp=map.getJSONObject("udp");
                synchronized(c.streamLock) {
                    String nextProfile=identity==null?"":identity.optString("profile_id","");
                    if(!c.profileId.equals(nextProfile)) {
                        stopConnection(c,"Kart changed · DISARMED");c.streamGeneration++;
                        c.lastImu=c.lastVideo=0;c.latest=null;c.battery=-1;c.cable=false;c.rssi=null;
                        synchronized(c.orientation){c.orientation.reset();}decoder.reset();recorder.stop();
                    }
                    c.profileId=nextProfile;c.name=identity==null?"Kart":identity.optString("name","Kart");c.serial=identity==null?"":identity.optString("serial","");
                    c.videoSource=udp.getInt("video");c.telemetrySource=udp.getInt("telemetry");c.lastSubscribe=System.nanoTime();
                }
            }
            JSONObject status=c.api.request("/v1/status",null);if(!c.alive)return;
            c.rawStatus=status.toString(2);JSONObject kart=status.optJSONObject("subscribed_kart"),health=status.optJSONObject("connection_health");
            c.connected=kart!=null&&kart.optBoolean("connected")&&c.profileId.equals(kart.optString("profile_id"));
            c.rssi=null;c.health="disconnected";
            if(health!=null&&c.profileId.equals(health.optString("profile_id"))) { c.health=health.optString("state","disconnected");if(!health.optBoolean("wifi_stale",true)&&!health.isNull("wifi_rssi_dbm"))c.rssi=health.getInt("wifi_rssi_dbm"); }
            c.lastStatus=System.nanoTime();c.error="";
            if(!c.connected)stopConnection(c,"Kart offline");
            JSONObject controlled=status.optJSONObject("controlled_kart");
            if(c.armed&&(controlled==null||!c.profileId.equals(controlled.optString("profile_id"))))stopConnection(c,"Gateway released control lease · DISARMED");
        }catch(Exception e){if(c.alive){c.connected=false;c.rssi=null;c.error="Gateway: "+message(e);stopConnection(c,"Gateway unavailable");}}
    }
    public void input(float steer,float throttle) { Connection c=connection;if(c==null)return;synchronized(c){c.steer=Math.abs(steer)<.08?0:steer;c.throttle=Math.abs(throttle)<.08?0:throttle;c.lastInput=System.nanoTime();} }
    public void calibrate() { Connection c=connection;if(c!=null)synchronized(c.orientation){c.orientation.calibrate();} }
    public String armBlock() { Connection c=connection;if(c==null||!c.alive)return "Connect first";synchronized(c){return block(c);} }
    private String block(Connection c) {
        if(!foreground)return "App must be in foreground";
        if(!c.config.permission().equals("control"))return "Control credential required";
        if(!c.connected||age(c.lastStatus)>1800||age(c.lastImu)>=1000)return "Fresh kart session and telemetry required";
        if(c.armed||c.armPending)return "Already armed or arming";
        if(Math.abs(c.steer)>.05||Math.abs(c.throttle)>.05)return "Release joystick before arming";
        return "";
    }
    public void arm() {
        Connection c=connection;if(c==null)return;
        final long attempt;
        synchronized(c){String reason=block(c);if(!reason.isEmpty()){c.control=reason;return;}c.armPending=true;attempt=++c.armGeneration;c.control="Requesting control lease…";}
        apiWorker.execute(()->{
            boolean granted=false;
            try {
                synchronized(c){if(!c.alive||!foreground||attempt!=c.armGeneration){c.armPending=false;return;}c.motor=socket(41020);}
                JSONObject result=c.api.request("/v1/arm",new JSONObject().put("movement_authorized",true).put("source_port",41020));granted=true;
                synchronized(c) {
                    if(!c.alive||!foreground||attempt!=c.armGeneration||!c.connected||age(c.lastImu)>=1000||Math.abs(c.steer)>.05||Math.abs(c.throttle)>.05)throw new IllegalStateException("Arm cancelled; release controls and confirm again");
                    int port=result.getInt("motor_udp");if(port<1024||port>65535)throw new IllegalArgumentException("Invalid motor port");
                    c.lease.arm(result.getLong("counter_start"),result.getLong("counter_count"));c.motor.connect(c.address,port);c.armPending=false;c.armed=true;c.control="ARMED · release joystick to stop";c.lastMotorTick=System.nanoTime();
                    send(c,0,0);c.motorTick=motorWorker.scheduleAtFixedRate(()->tick(c),0,80,TimeUnit.MILLISECONDS);
                }
            }catch(Exception e){stopConnection(c,"Arm failed: "+message(e));if(granted)try{c.api.request("/v1/disarm",new JSONObject());}catch(Exception ignored){} }
        });
    }
    private void tick(Connection c) {
        synchronized(c) {
            if(!c.armed)return;
            long now=System.nanoTime();
            if((now-c.lastMotorTick)/1_000_000>200||!c.alive||!MotorLease.safe(foreground,c.connected&&age(c.lastStatus)<1800,age(c.lastImu),c.steer,c.throttle,age(c.lastInput))) { stopConnection(c,"Watchdog / stale session · DISARMED");return; }
            c.lastMotorTick=now;
            try{send(c,c.steer,c.throttle);}catch(Exception e){stopConnection(c,"Motor sender failed · DISARMED");}
        }
    }
    private void send(Connection c,float steer,float throttle) throws Exception {
        byte[] p=c.lease.packet(steer,throttle);if(p==null)throw new IllegalStateException("Counter reservation exhausted");c.motor.send(new DatagramPacket(p,p.length));
    }
    private void stopConnection(Connection c,String reason) {
        boolean release;
        synchronized(c) {
            release=c.armed||c.armPending;c.armGeneration++;c.armPending=false;
            if(c.armed&&c.motor!=null)for(int i=0;i<3&&c.lease.active();i++)try{send(c,0,0);}catch(Exception ignored){}
            c.armed=false;c.lease.clear();c.steer=c.throttle=0;c.control=reason;
            if(c.motorTick!=null){c.motorTick.cancel(false);c.motorTick=null;}if(c.motor!=null){c.motor.close();c.motor=null;}
        }
        if(release&&c.api!=null&&!apiWorker.isShutdown())apiWorker.execute(()->{try{c.api.request("/v1/disarm",new JSONObject());}catch(Exception ignored){}});
    }
    public void stop(String reason) { Connection c=connection;if(c!=null)stopConnection(c,reason); }
    public void disconnect() {
        Connection c=connection;connection=null;
        if(c!=null){c.alive=false;stopConnection(c,"Disconnected · DISARMED");if(c.poll!=null)c.poll.cancel(false);c.closeSockets();}
        recorder.stop();decoder.reset();
    }
    public static long age(long time) { return time==0?Long.MAX_VALUE:Math.max(0,(System.nanoTime()-time)/1_000_000); }
    private static String message(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s;}
    @Override public void close(){disconnect();decoder.close();apiWorker.shutdown();motorWorker.shutdownNow();}
    public static final class Connection {
        final ClientConfig config;final String host,profile;volatile boolean alive=true,connected;
        volatile GatewayTransport api;volatile InetAddress address;
        volatile DatagramSocket video,telemetry,motor;
        final Object streamLock=new Object();volatile long streamGeneration;
        final MotorLease lease=new MotorLease();public final Orientation orientation=new Orientation();
        ScheduledFuture<?> poll,motorTick;long armGeneration,lastMotorTick;
        public volatile boolean armed,armPending,cable;
        public volatile String name="Kart",serial="",profileId="",health="disconnected",error="",control="DISARMED",rawStatus="";
        public volatile Integer rssi;public volatile int battery=-1;volatile int videoSource=-1,telemetrySource=-1;
        public volatile long videoPackets,videoBytes,telemetryPackets,frames,gaps,lastImu,lastVideo,lastStatus,lastSubscribe,lastInput;
        float steer,throttle;public volatile Telemetry latest;
        Connection(ClientConfig config,String host,String profile){this.config=config;this.host=host;this.profile=profile;}
        void closeSockets(){if(video!=null)video.close();if(telemetry!=null)telemetry.close();if(motor!=null)motor.close();}
    }
}
