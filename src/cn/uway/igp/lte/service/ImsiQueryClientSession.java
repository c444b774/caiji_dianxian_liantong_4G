package cn.uway.igp.lte.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.igp.lte.service.ImsiJoinService.DistributeServer;
import cn.uway.util.OperatorFileSerial;
import cn.uway.util.OperatorFileSerial.EOPERATOR_FILE_MODE;
import cn.uway.util.OperatorFileSerial.E_ENDIAN_MODE;

public class ImsiQueryClientSession extends AbsImsiQuerySession {
	protected DistributeServer distributeServer;
	protected OperatorFileSerial fsRead;
	protected OperatorFileSerial fsWrite;
	protected long taskID;
	
	private static final Logger log = LoggerFactory.getLogger(ImsiQueryClientSession.class);
	
	public ImsiQueryClientSession(DistributeServer distributeServer, long taskID) {
		super(distributeServer.index, distributeServer.ip, distributeServer.port);
		this.distributeServer = distributeServer;
		this.taskID = taskID;
		
		try {
			fsRead = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Read, E_ENDIAN_MODE.e_Endian_BE, recvBuff);
			fsWrite = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Write, E_ENDIAN_MODE.e_Endian_BE, sendBuff);
		} catch (Exception e) {
			log.error("ImsiQueryClientSession::ImsiQueryClientSession() has error ocurred.", e);
		}
	}
	
	protected boolean connect() {
		if (socket != null && socket.isConnected())
			return true;
		
		this.socket = new Socket();
		ImsiJoinService.initSocket(socket);
		try {
			InetAddress addr = InetAddress.getByName(distributeServer.ip);
			SocketAddress serverAddr = new InetSocketAddress(addr, distributeServer.port);
			this.socket.connect(serverAddr, 5*1000);
			this.in = socket.getInputStream();
			this.out = socket.getOutputStream();
			
			log.debug("连接到IMSI查询服务器[{}]{}::{}成功.", new Object[] {distributeServer.index, distributeServer.ip, distributeServer.port});
			
			nErrCount = 0;
			return true;
		} catch (IOException e) {
			log.error("连接到IMSI查询服务器[{}]{}::{}失败!!!", new Object[] {distributeServer.index, distributeServer.ip, distributeServer.port}, e);
		}
		
		return false;
	}
	
	public ImsiRequestResult isCacheReady(long fileTime) throws Exception {
		// 确保服务器已经连接好
		ensureServerSocketAvailable();
		
		try {
			// 组装查询包
			fsWrite.setCurrPosition(0);
			fsWrite.write((byte)ImsiRequestResult.REQUEST_CACHE_HAS_READY);
			fsWrite.write((long)fileTime);
			
			writeRequest((int)fsWrite.getCurrFileWriteLength());
		} catch (Exception e) {
			log.error("和服务端通讯出错", e);
			this.close();
			return null;
		}
		
		// 读取返回结果
		if (readResponse(false) < 1) {
			this.close();
			return null;
		}
		
		//　解析返回结果
		ImsiRequestResult result = new ImsiRequestResult();
		result.requestServer = this.distributeServer;
		fsRead.setCurrPosition(0);
		int msgCount = fsRead.read_short();
		if (msgCount != 1) {
			log.warn("isCacheReady读取返回的消息个数不正确");
		}
		result.value = fsRead.read_byte();
		result.maxServerTimeInCache = fsRead.read_long();
		
		return result;
	}
	
	/**
	 * 批量消息匹配
	 * @param imsiQueryParams
	 * @param queryCount
	 * @return
	 * @throws Exception
	 */
	public ImsiRequestResult[] matchIMSIInfo(ImsiQueryParam[] imsiQueryParams, short queryCount) throws Exception {
		if (matchIMSIInfoRequest(imsiQueryParams, queryCount))
			return matchIMSIInfoTakeResult(imsiQueryParams, queryCount);
		
		return null;
	}
	
	public boolean matchIMSIInfoRequest(ImsiQueryParam[] imsiQueryParams, short queryCount) throws Exception {
		if (imsiQueryParams == null || imsiQueryParams.length < queryCount || queryCount < 1)
			return false;
		
		// 确保服务器已经连接好
		ensureServerSocketAvailable();
		
		// 组装查询包
		try {
			int nQuerySerialNo = this.querySerialNo + 1;
			fsWrite.setCurrPosition(0);
			fsWrite.write((byte)ImsiRequestResult.REQUEST_IMSI_INFO);
			fsWrite.write((int)nQuerySerialNo);
			fsWrite.write((short)queryCount);
			
			//　系列化每个查询请求
			for (int i=0; i<queryCount; ++i) {
				imsiQueryParams[i].write(fsWrite);
			}
			
			// 发送查询请求
			writeRequest((int)fsWrite.getCurrFileWriteLength());
			this.querySerialNo = nQuerySerialNo;
		} catch (Exception e) {
			log.error("和服务端通讯出错", e);
			this.close();
			return false;
		}
		
		return true;
	}
	
	public ImsiRequestResult[] matchIMSIInfoTakeResult(ImsiQueryParam[] imsiQueryParams, short queryCount) throws Exception {
		if (imsiQueryParams == null || imsiQueryParams.length < queryCount || queryCount < 1)
			return null;
		
		// 确保服务器已经连接好
		ensureServerSocketAvailable();
		
		// 编码
		try {
			// 读取返回结果
			int packLength = readResponse(false);
			if (packLength < 1) {
				log.error("readResponse()读取失败，已发送不含包头字节数:{} queryCount:{} readResponseLenth:{}", new Object[]{fsWrite.getCurrFileWriteLength(), queryCount, packLength});
				this.close();
				return null;
			}
			
			//　解析返回结果
			fsRead.setCurrPosition(0);
			int msgCount = fsRead.read_short();
			if (msgCount != queryCount) {
				log.warn("查询尺寸和返回尺寸不等，已发送不含包头字节数:{} queryCount:{} readResponseLenth:{} readMsgCount:{}", new Object[]{fsWrite.getCurrFileWriteLength(), queryCount, packLength, msgCount});
				return null;
			}
			
			ImsiRequestResult[] results = new ImsiRequestResult[imsiQueryParams.length];
			for (int i=0; i<msgCount; ++i) {
				results[i] = new ImsiRequestResult();
				results[i].value = fsRead.read_byte();
				if (results[i].value == ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS) {
					results[i].imsi = fsRead.read_long();
					results[i].msisdn = fsRead.read_string(16, true);
				} else {
					// 查询失败跳过imsi和msisdn位置
					fsRead.skip(8+16);
				}
			}
			
			if (packLength != fsRead.getCurrPosition()) {
				log.warn("包读写未处理完整.　已读:{}, 总长:{}", fsRead.getCurrPosition(), packLength);
			}
			
			return results;
		} catch (IOException e) {
			this.close();
			throw e;
		}
	}
	
	protected void ensureServerSocketAvailable() throws Exception {
		if (this.socket != null && socket.isConnected())
			return;
		
		int nTryCount = 0;
		while (nTryCount++ < 3) {
			if (!connect()) {
				Thread.sleep(5*1000);
				continue;
			}
			
			if (!handShake()) {
				this.close();
				continue;
			}
			
			return;
		}
		
		String errMsg = "连接到服务器["+ distributeServer.index + "]" + distributeServer.ip + ":" + distributeServer.port + "错误次数超限，下次再试.";
		log.error(errMsg);
		
		throw new Exception(errMsg);
	}
	
	private boolean handShake() {
		try {
			// 组装查询包
			fsWrite.setCurrPosition(0);
			fsWrite.write((byte)ImsiRequestResult.REQUEST_CONN_HANDSHAKE);
			fsWrite.write(IMSI_QUERY_SERVICE_VERSION);
			fsWrite.write((long)this.taskID);
			
			// 发送握手信息
			writeRequest((int)fsWrite.getCurrFileWriteLength());
			
			// 读取返回结果
			if (readResponse(false) < 1) {
				log.error("读取服务器返回结果失败");
				return false;
			}
			
			//　解析返回结果
			fsRead.setCurrPosition(0);
			int msgCount = fsRead.read_short();
			if (msgCount != 1) {
				log.warn("handShake读取返回的消息个数不正确");
			}
			byte handshakeFlag = fsRead.read_byte();
			long serverVersion = fsRead.read_long();
			if (handshakeFlag != ImsiRequestResult.RESPONSE_CONN_HANDSHAKE_OK) {
				log.error("服务器返回信息状态不正确．handshakeFlag={} client version:{} server version:{}", new Object[] {handshakeFlag, IMSI_QUERY_SERVICE_VERSION, serverVersion});
				return false;
			}
			
			
			return true;
		} catch (Exception e) {
			log.error("握手失败{}", e.getMessage());
		}
		
		return false;
	}
}
