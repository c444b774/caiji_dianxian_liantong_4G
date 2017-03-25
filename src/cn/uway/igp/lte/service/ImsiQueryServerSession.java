package cn.uway.igp.lte.service;

import java.io.IOException;
import java.net.Socket;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.igp.lte.service.LteCoreCommonDataManager.MatchParam;
import cn.uway.util.OperatorFileSerial;
import cn.uway.util.OperatorFileSerial.EOPERATOR_FILE_MODE;
import cn.uway.util.OperatorFileSerial.E_ENDIAN_MODE;

public class ImsiQueryServerSession extends AbsImsiQuerySession
		implements
			Runnable {

	protected ImsiJoinService service;

	protected long nQueryTotal;

	protected long nQuerySuccessedCount;

	protected OperatorFileSerial fsRead;

	protected OperatorFileSerial fsWrite;

	protected int clientVersion;

	protected long clientTaskID;

	protected String remoteClientInfo;

	protected ImsiQueryParam imsiQueryParam;

	private static final Logger log = LoggerFactory
			.getLogger(ImsiQueryServerSession.class);

	public ImsiQueryServerSession(ImsiJoinService service, int sessionID,
			String remoteIP, int remotePORT, Socket socket) throws IOException {
		super(sessionID, remoteIP, remotePORT);
		this.remoteClientInfo = buildReomoteClientInfo();
		this.service = service;
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
		try {
			fsRead = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Read,
					E_ENDIAN_MODE.e_Endian_BE, recvBuff);
			fsWrite = new OperatorFileSerial(EOPERATOR_FILE_MODE.e_Write,
					E_ENDIAN_MODE.e_Endian_BE, sendBuff);
		} catch (Exception e) {
			log.error(
					"ImsiQueryServerSession::ImsiQueryServerSession() has error ocurred. "
							+ remoteClientInfo, e);
		}
		this.imsiQueryParam = new ImsiQueryParam();
	}

	@Override
	public void run() {
		service.registerSession(this);

		boolean bPassiveClosed = false;
		int packLength = -1;
		while (in != null && out != null) {
			try {
				packLength = readResponse(true);
				if (packLength < 1) {
					break;
				}
			} catch (Exception e) {
				bPassiveClosed = true;
				break;
			}

			if (!process(packLength))
				break;
		}

		if (bPassiveClosed)
			log.debug("客户端连接主动关闭.{}", new Object[]{remoteClientInfo});
		else
			log.debug("已切断与客户端连接.{}", new Object[]{remoteClientInfo});

		service.unRegisterSession(this);
		close();
	}

	public String buildReomoteClientInfo() {
		StringBuilder sb = new StringBuilder();
		sb.append("remoteip=").append(this.remoteIP).append(" port=")
				.append(this.remotePORT).append(" sessionID=")
				.append(this.sessionID).append(" clientVersion=")
				.append(this.clientVersion).append(" clientTaskID=")
				.append(this.clientTaskID);

		return sb.toString();
	}

	public boolean process(final int packLength) {
		// 查询结果
		byte responseValue = ImsiRequestResult.RESPONSE_ERROR;
		long nResponseParam = -1;
		//byte[] byteResponseParam = null;

		try {
			if (nQueryTotal > 0 && (nQueryTotal % 100000) == 0) {
				log.debug("{} 已查询了{}次IMSI请求.其中成功关联{}次. 关联成功率：{}%", new Object[]{
						remoteClientInfo, nQueryTotal, nQuerySuccessedCount, 
						((int)((nQuerySuccessedCount/((double)nQueryTotal))*10000.0f+0.4999f))/100.0f});
			}

			boolean bMsgProcessed = false;
			int msgCount = 1;
			
			//复位读写缓冲区指针
			fsRead.setCurrPosition(0);
			fsWrite.setCurrPosition(0);
			
			// 解析查询参数
			byte requestType = fsRead.read_byte();
			switch (requestType) {
				case ImsiRequestResult.REQUEST_IMSI_INFO :
					processImsiQuery();
					bMsgProcessed = true;
					break;
				case ImsiRequestResult.REQUEST_CONN_HANDSHAKE :
					this.clientVersion = fsRead.read_int();
					this.clientTaskID = fsRead.read_long();
					this.remoteClientInfo = buildReomoteClientInfo();
					
					nResponseParam = IMSI_QUERY_SERVICE_VERSION;
					Thread.currentThread().setName(Thread.currentThread().getName() + "@" + this.clientTaskID);
					if (IMSI_QUERY_SERVICE_VERSION >= this.clientVersion && this.clientVersion >= 2) {
						responseValue = ImsiRequestResult.RESPONSE_CONN_HANDSHAKE_OK;
						log.debug("连接握手成功. taskid:{} version:{}", new Object[]{clientTaskID, clientVersion});
					} else {
						responseValue = ImsiRequestResult.RESPONSE_CONN_HANDSHAKE_INCORRECT_VERSION;
						log.debug("连接握手客户端和服务端版本不一致. taskid:{} client version:{} server version:{}", new Object[]{clientTaskID, clientVersion, IMSI_QUERY_SERVICE_VERSION});
					}
					break;
				case ImsiRequestResult.REQUEST_CACHE_HAS_READY :
					if (service.lteCoreCommonDataManager == null) {
						responseValue = ImsiRequestResult.RESPONSE_CACHE_IS_LOADING;
						nResponseParam = 0;
					} else {
						nResponseParam = service.lteCoreCommonDataManager.maxTimeFileInCache == null
								? 0
								: service.lteCoreCommonDataManager.maxTimeFileInCache;
						long fileTime = fsRead.read_long();
						if (service.lteCoreCommonDataManager
								.isCacheLoadedReady(fileTime)) {
							if (service.lteCoreCommonDataManager
									.isCacheReady(new Date(fileTime))) {
								responseValue = ImsiRequestResult.RESPONSE_CACHE_IS_READY;
							} else {
								responseValue = ImsiRequestResult.RESPONSE_CACHE_NOT_READY;
							}
						} else {
							responseValue = ImsiRequestResult.RESPONSE_CACHE_IS_LOADING;
						}
					}
					break;
				default :
					responseValue = ImsiRequestResult.RESPONSE_ERROR;
					log.error(
							"ImsiQueryServerSession::processQuery() 不能识别的消息类型. request type={} {}",
							new Object[]{requestType, remoteClientInfo});
					break;
			}
			
			if (packLength != fsRead.getCurrPosition()) {
				log.warn("包读写未处理完整.　已读:{}, 总长:{}", fsRead.getCurrPosition(), packLength);
			}

			// 组装返回的数据包
			if (!bMsgProcessed) {
				fsWrite.write((short) msgCount);
				fsWrite.write((byte) responseValue);
				fsWrite.write((long) nResponseParam);
				fsWrite.skip(16);
				//fsWrite.write(byteResponseParam, 0, 16);
			}

			// 发送查询请求
			writeRequest((int) fsWrite.getCurrFileWriteLength());

			return true;
		} catch (Exception e) {
			log.error(
					"ImsiQueryServerSession::processQuery() has error occured. "
							+ remoteClientInfo, e);
		}

		return false;
	}

	public void processImsiQuery() throws IOException {
		long queryStartTime = System.currentTimeMillis();
		
		// 读取查询序列号
		if (this.clientVersion > 2) {
			this.querySerialNo = fsRead.read_int();
		}
		
		short queryCount = fsRead.read_short();
		if (queryCount > ImsiQueryParam.MAX_QUERY_SIZE) {
			log.error("查询个数{}大于{}个", queryCount, ImsiQueryParam.MAX_QUERY_SIZE);
			fsWrite.write((short)0);
			return;
		}
		
		fsWrite.write((short)queryCount);
		for (int i = 0; i < queryCount; ++i) {
			boolean bValid = true;
			if (!imsiQueryParam.read(fsRead) || !imsiQueryParam.isValid()) {
				bValid = false;
			}
			
			if (bValid) {
				MatchParam result = service.lteCoreCommonDataManager
						.matchLteCoreCommonDataEntry(imsiQueryParam.cdrTime,
								imsiQueryParam.mmeUeS1apID,
								imsiQueryParam.mtmsi, imsiQueryParam.mmegi,
								imsiQueryParam.mmec);
				if (result.entry != null) {
					fsWrite.write((byte) ImsiRequestResult.RESPONSE_IMSI_QUERY_SUCCESS);
					fsWrite.write((long) result.entry.imsi);
					fsWrite.write(result.entry.msisdn, 0, 16);
					
					//抽样打印请求结果
					if (nQueryTotal < 21) {
						log.debug("matched sucessed. cdrtime:{} mmeUeS1apID:{} mtmsi:{} mmegi:{} mmec:{} imsi:{} msisdn:{}",
								new Object[]{new Date(imsiQueryParam.cdrTime), imsiQueryParam.mmeUeS1apID, imsiQueryParam.mtmsi, imsiQueryParam.mmegi, imsiQueryParam.mmec, result.entry.imsi, converBytesToString(result.entry.msisdn)});
					}
					
					++nQuerySuccessedCount;
					continue;
				}
				
				//抽样打印请求结果
				if (nQueryTotal < 21) {
					log.debug("matched faild. cdrtime:{} mmeUeS1apID:{} mtmsi:{} mmegi:{} mmec:{} cause:{}", 
							new Object[]{new Date(imsiQueryParam.cdrTime), imsiQueryParam.mmeUeS1apID, imsiQueryParam.mtmsi, imsiQueryParam.mmegi, imsiQueryParam.mmec, result.matchFaildCause});
				}
			} else {
				//抽样打印请求结果
				if (nQueryTotal < 21) {
					log.debug("matched faild. cdrtime:{} mmeUeS1apID:{} mtmsi:{} mmegi:{} mmec:{} cause:invalid request.", 
							new Object[]{new Date(imsiQueryParam.cdrTime), imsiQueryParam.mmeUeS1apID, imsiQueryParam.mtmsi, imsiQueryParam.mmegi, imsiQueryParam.mmec});
				}
			}
			
			fsWrite.write((byte) ImsiRequestResult.RESPONSE_IMSI_QUERY_FAILD);
			//写入一个空的imsi和msisdn位
			fsWrite.skip(8 + 16);
		}
		
		nQueryTotal += queryCount;
		
		long timeElapsed = System.currentTimeMillis() - queryStartTime;
		if (timeElapsed > (30*1000l)) {
			log.warn("请求查询时间过长．序号：{} 查询个数:{} 查询用时间{}毫秒", new Object[]{this.querySerialNo, queryCount, timeElapsed});
		}
	}
	
	/**
	 * 将bytes转换成字符串，去掉0结尾的空位
	 * @param bytes
	 * @return
	 */
	private String converBytesToString(byte[] bytes) {
		if (bytes == null)
			return null;
		
		int i=0;
		while (i<bytes.length && bytes[i] != '\0')
			++i;
		
		return new String(bytes, 0, i);
	}
}
