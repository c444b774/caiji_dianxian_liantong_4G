package cn.uway.igp.lte.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.external.AbsExternalService;

public class ImsiJoinService implements AbsExternalService {
	public static class DistributeServer {
		public int index;
		public String ip;
		public int port;
	}
	
	private static final Logger log = LoggerFactory.getLogger(ImsiJoinService.class);
	
	/**
	 * spring config.ini注入的参数.
	 * currDistributeServerIndex: 当前lte核心网IMSI查询服务器分布式索引
	 * 							(部署服务器端，需要配置此参数，IGP采集端可忽略.该参数在网内不可重复，从1开始)
	 * distributeServers: 分布式服务器列表([IP1]:[PORT],[IP2]:[PORT],[IP3]:[PORT],...)
	 */
	protected String currDistributeServerIndex;
	protected String distributeServers;
	
	/**
	 * 分布式服务器列表和当前服务器索引(程序内部，索引从0开始)
	 */
	protected List<DistributeServer> distributeServersList;
	protected Integer nCurrDistributeServerIndex; 
	
	/**
	 * 已连接的session列表
	 */
	protected List<ImsiQueryServerSession> sessionList;
	
	/**
	 * 核心网数据管理器
	 */
	protected LteCoreCommonDataManager lteCoreCommonDataManager;
	
	// 可同时接受准备连接的socket个数
	private static final int connPendingSocketNumber = 60;
	
	// socket的超时时间
	private static final int TIME_OUT_MILLSECONDS = (5*60*1000);
	
	public ImsiJoinService() {
		sessionList = new LinkedList<ImsiQueryServerSession>();
	}
	
	public void init() throws Exception {
		if (currDistributeServerIndex == null || currDistributeServerIndex.startsWith("${")) {
			throw new Exception("在config.ini中未配置[system.lte.imsiQueryService.currDistributeServerIndex]和[system.lte.imsiQueryService.distributeServers]两个参数");
		}
		
		try {
			distributeServersList = new ArrayList<DistributeServer>(100);
			String[] servers = distributeServers.split("\\,");
			for (String server:servers) {
				server = server.trim();
				if (server.length()<9)
					continue;
				
				DistributeServer distributeServer = new DistributeServer();
				int nSplitIndex = server.indexOf(':');
				distributeServer.ip = server.substring(0, nSplitIndex);
				distributeServer.port = Integer.parseInt(server.substring(nSplitIndex+1));
				distributeServer.index = distributeServersList.size();
				distributeServersList.add(distributeServer);
			}
			
			nCurrDistributeServerIndex = Integer.valueOf(currDistributeServerIndex);
			
			if (nCurrDistributeServerIndex == null || distributeServersList.size() < 1) {
				throw new Exception("config.ini中[system.lte.imsiQueryService.currDistributeServerIndex]和[system.lte.imsiQueryService.distributeServers]两个参数设置不正确");
			}
			--nCurrDistributeServerIndex;
			
			log.debug("当前设置的LTE核心网查询服务个数：{}", distributeServersList.size());
			int i=0;
			for (DistributeServer distributeServer : distributeServersList) {
				log.debug("服务器[{}] ip={}:{}",  new Object[]{++i, distributeServer.ip, distributeServer.port});
			}
			
			if (nCurrDistributeServerIndex >= 0) {
				DistributeServer distributeServer = distributeServersList.get(nCurrDistributeServerIndex);
				if (distributeServer != null) {
					log.debug("当前分布式服务器 ip={}:{}",  new Object[]{distributeServer.ip, distributeServer.port});
				} else {
					throw new Exception("分布式索引配置错误.");
				}
			}
		} catch (Exception e) {
			throw new Exception("解析分布式服务式参数设置出错" + e.getMessage(), e);
		}
	}
	
	@Override
	public void run() {
		ServerSocket skListener = null;
		try {
			lteCoreCommonDataManager = LteCoreCommonDataManager.getReadInstance();
			if (!lteCoreCommonDataManager.isEnableState()) {
				log.debug("核心网数据未开启，请在config.ini设置正确的核心网数据缓存目录");
				return;
			}
			
			InetAddress addServer = InetAddress.getLocalHost();
			skListener = new ServerSocket();
			skListener.setReuseAddress(false);
			
			// 默认连接端口
			DistributeServer distributeServer = distributeServersList.get(nCurrDistributeServerIndex);
			if (distributeServer == null) {
				log.debug("无法获取到本机分布式服务配置");
				return;
			}
			
			lteCoreCommonDataManager.startLoadCache(distributeServersList.size(), distributeServer.index);
			
			InetSocketAddress addrServer = new InetSocketAddress(distributeServer.port);
			skListener.bind(addrServer, connPendingSocketNumber);
			log.debug("服务器连接监听线程已成功启动监听模式,.. hostname={} ip={} port={}", 
					new Object[]{addServer.getHostName(), addServer.getHostAddress(), distributeServer.port});
			
			while (true) {
				Socket skClient = skListener.accept();
				initSocket(skClient);
				InetSocketAddress addrClient = (InetSocketAddress)skClient.getRemoteSocketAddress();
				log.debug("已建立好客户端连接.remote:{},执行中...", addrClient);
				
				ImsiQueryServerSession serverSession = new ImsiQueryServerSession(this, 0, addrClient.getAddress().getHostAddress(), addrClient.getPort(), skClient);
				Thread thread = new Thread(serverSession, addrClient.toString());
				thread.start();
			}
			
		} catch (IOException e) {
			log.error("服务器连接监听线程出现异常，被迫终止．", e);
		} finally {
			if (skListener != null && !skListener.isClosed()) {
				try {
					skListener.close();
				} catch (IOException e) {}
			}
		}
		
		log.debug("ImsiJoinServicerun done.");
	}
	
	/**
	 * 初始化socket参数
	 * @param socket
	 */
	public static void initSocket(Socket socket) {
		try {
			/**
			 * 对于这种查询服务，不要让底层socket使用Negale延时算法，
			 * 需要极速查询返回，发送和接受缓冲区要使用很小.
			 */
			socket.setTcpNoDelay(true);
			socket.setSendBufferSize(512);
			socket.setReceiveBufferSize(512);
			socket.setReuseAddress(false);
			socket.setKeepAlive(true);
			socket.setSoTimeout(TIME_OUT_MILLSECONDS);
		} catch (SocketException e) {
			log.warn("设置socket参数失败.",e);
		}
	}
	
	public void registerSession(ImsiQueryServerSession serverSession) {
		synchronized (sessionList) {
			sessionList.add(serverSession);
			log.debug("已成功将{}:{}加入线程处理列表, 当前客户端连接个数:{}", new Object[]{serverSession.remoteIP, serverSession.remotePORT, sessionList.size()});
		}
	}
	
	public void unRegisterSession(ImsiQueryServerSession serverSession) {
		synchronized (sessionList) {
			sessionList.remove(serverSession);
			log.debug("已成功将{}:{}从线程列表中移除, 当前客户端连接个数:{}", new Object[]{serverSession.remoteIP, serverSession.remotePORT, sessionList.size()});
		}
	}

	public String getCurrDistributeServerIndex() {
		return currDistributeServerIndex;
	}
	
	public void setCurrDistributeServerIndex(String currDistributeServerIndex) {
		this.currDistributeServerIndex = currDistributeServerIndex;
	}
	
	public String getDistributeServers() {
		return distributeServers;
	}
	
	public void setDistributeServers(String distributeServers) {
		this.distributeServers = distributeServers;
	}
	
	public List<DistributeServer> getDistributeServersList() {
		return distributeServersList;
	}
	
	public void setDistributeServersList(
			List<DistributeServer> distributeServersList) {
		this.distributeServersList = distributeServersList;
	}
}
