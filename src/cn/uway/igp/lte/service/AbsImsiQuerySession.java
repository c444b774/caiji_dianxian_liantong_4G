package cn.uway.igp.lte.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.igp.lte.service.ImsiJoinService.DistributeServer;
import cn.uway.util.OperatorFileSerial;

public class AbsImsiQuerySession {
	/**
	 * IMSI查询请求参数
	 * @ 2016-12-19
	 */
	public static class ImsiQueryParam {
		public Object queryContext;
		
		/**
		 * 无线网话单时间
		 */
		public long cdrTime;

		public Long mmeUeS1apID;

		public Long mtmsi;

		public Integer mmegi;

		public Integer mmec;
		
		/** <pre>
		 * 要查找的Key个数，最多两个
		 * 0:tmsi
		 * 1:mmes1apid 
		 * </pre>
		 */
		public final static int QueryKeyCount = 2;
		
		/**
		 * 最大查询批次数量
		 */
		public static final int MAX_QUERY_SIZE = 20;
		
		// 无参数的构造，只能本包，提供给sessionServer进行系列化反解使用
		protected ImsiQueryParam() {
			//
		}
	
		public ImsiQueryParam(Object queryContext, long cdrTime, Long mmeUeS1apID, Long mtmsi,
				Integer mmegi, Integer mmec) {
			this.queryContext = queryContext;
			this.cdrTime = cdrTime;
			this.mmeUeS1apID = mmeUeS1apID;
			this.mtmsi = mtmsi;
			this.mmegi = mmegi;
			this.mmec = mmec;
		}

		public boolean write(OperatorFileSerial fsWrite) throws IOException {
			fsWrite.write((long)cdrTime);
			fsWrite.write((int)(mmeUeS1apID == null?0xFFFFFFFF:mmeUeS1apID));
			fsWrite.write((int)(mtmsi == null?0xFFFFFFFF:mtmsi));
			fsWrite.write((short)(mmegi == null?0xFFFF:mmegi));
			fsWrite.write((short)(mmec == null?0xFFFF:mmec));
			
			return true;
		}
		
		public boolean read(OperatorFileSerial fsRead) throws IOException {
			cdrTime = fsRead.read_long();
			mmeUeS1apID = fsRead.read_uint();
			mtmsi = fsRead.read_uint();
			mmegi = fsRead.read_ushort();
			mmec = fsRead.read_ushort();
			
			return true;
		}
		
		/**
		 * 检测这个包是否正常的
		 * @return
		 */
		public boolean isValid() {
			if (mmegi == null || mmegi == 0xFFFFL 
					|| mmec == null || mmec == 0xFFFFL)
				return false;
			
			// 对如果两个查询主键都无效的直接返回
			if ( (mmeUeS1apID == null || mmeUeS1apID == 0xFFFFFFFFL)
					&& (mtmsi==null || mtmsi == 0xFFFFFFFFL) )
				return false;
			
			return true;
		}
		
		/**
		 * 计算查找的key所在分布式服务器索引
		 * @param queryKeyIndex (0:tmsi; 1:mmes1apid) 
		 * @param sessionLength 服务器个数
		 * @return
		 * @throws Exception
		 */
		public int getDistributeSessionIndex(int queryKeyIndex, int sessionLength) throws Exception {
			if (queryKeyIndex == 0) {
				return (int)(mtmsi % sessionLength);
			} else if (queryKeyIndex == 0) {
				return (int)(mmeUeS1apID % sessionLength);
			}
			
			throw new Exception("invalid param QueryKeyIndex=" + queryKeyIndex);
		}
	}

	/**
	 * IMSI查询请求返回结构 @ 2016-3-11
	 */
	public static class ImsiRequestResult {

		/**
		 * 查询缓存是否准备好消息
		 */
		public static final byte REQUEST_CACHE_HAS_READY = 1;

		/**
		 * 查询IMSI消息
		 */
		public static final byte REQUEST_IMSI_INFO = 2;

		/**
		 * 连接握手
		 */
		public static final byte REQUEST_CONN_HANDSHAKE = 3;

		/**
		 * 消息查询报错
		 */
		public static final byte RESPONSE_ERROR = -99;

		/**
		 * 消息查询成功
		 */
		public static final byte RESPONSE_IMSI_QUERY_SUCCESS = 0;

		/**
		 * 消息查询失败
		 */
		public static final byte RESPONSE_IMSI_QUERY_FAILD = -1;

		/**
		 * 连接握手成功
		 */
		public static final byte RESPONSE_CONN_HANDSHAKE_OK = 1;
		
		/**
		 * 连接握手(不正确的版本号)
		 */
		public static final byte RESPONSE_CONN_HANDSHAKE_INCORRECT_VERSION = -1;

		/**
		 * 缓存已准备好
		 */
		public static final byte RESPONSE_CACHE_IS_READY = 100;

		/**
		 * 核心网缓存正在加载
		 */
		public static final byte RESPONSE_CACHE_IS_LOADING = -100;

		/**
		 * 缓存未加载到数据时间
		 */
		public static final byte RESPONSE_CACHE_NOT_READY = -101;

		public byte value;

		public long imsi;

		public String msisdn;

		public long maxServerTimeInCache;

		public DistributeServer requestServer;

		public String getRequestServerInfo() {
			if (requestServer == null)
				return "";

			return requestServer.ip + ":" + requestServer.port;
		}

		public static String getResponseValueDesc(byte value) {
			switch (value) {
				case RESPONSE_ERROR :
					return "请求类型错误";
				case RESPONSE_IMSI_QUERY_SUCCESS :
					return "IMSI请求查询成功";
				case RESPONSE_IMSI_QUERY_FAILD :
					return "未关联到对应的IMSI";
				case RESPONSE_CACHE_IS_READY :
					return "服务端内存已准备就绪";
				case RESPONSE_CACHE_IS_LOADING :
					return "服务端内存正在加载中．";
				case RESPONSE_CACHE_NOT_READY :
					return "服务端缓存未达到时间关联条件";
				default :
					break;
			}

			return "未知的返回值类型";
		}
	}
	
	/**
	 * IMSI查询服务的版本
	 */
	public static final int IMSI_QUERY_SERVICE_VERSION = 3;

	protected int sessionID;

	protected String remoteIP;

	protected int remotePORT;

	protected Socket socket;

	protected InputStream in;

	protected OutputStream out;

	protected byte[] headerBuff;
	
	protected byte[] sendBuff;
	protected byte[] recvBuff;

	protected int nErrCount;
	
	protected int querySerialNo;

	private static final Logger log = LoggerFactory
			.getLogger(AbsImsiQuerySession.class);
	
	/**
	 * 如果改变了报文格式，最好每次将消息头改一下，以确保能检测出来
	 */
	private static final int MSG_HEADER = 0x6A6B6C7D;

	public AbsImsiQuerySession(int sessionID, String remoteIP, int remotePORT) {
		socket = null;
		this.sessionID = sessionID;
		this.remoteIP = remoteIP;
		this.remotePORT = remotePORT;
		headerBuff = new byte[6];
		sendBuff = new byte[4096];
		recvBuff = new byte[4096];
		nErrCount = 0;
	}

	/**
	 * 如果对端关闭，直接抛出异常， 如果读取出错次数超限，则返回false
	 * 
	 * @param buff
	 * @param offset
	 * @param length
	 * @return
	 * @throws IOException
	 */
	private boolean safeRead(byte[] buff, int offset, int length,
			boolean ignoreTimeOut) throws IOException {
		int sizeRight = length;
		while (sizeRight > 0) {
			int nRead = 0;
			try {
				nRead = in.read(buff, offset, sizeRight);
			} catch (IOException e) {
				if ((e instanceof SocketTimeoutException) && (sizeRight == length)
						&& ignoreTimeOut) {
					try {
						Thread.sleep(1);
						continue;
					} catch (InterruptedException e1) {
						log.error("thread sleep has error ocurred.", e1);
					}
				}

				log.debug(
						"socket 读取错误. remoteip={} port={} sessionID={} 应读取:{}byte 已读取{}byte 错误原因:{}",
						new Object[]{remoteIP, remotePORT, sessionID, length,
								offset + nRead, e.getMessage()});
				log.error("error info:", e);

				return false;
			}

			if (nRead < 0) {
				this.close();
				throw new IOException("对端已将连接主动关闭. remoteip=" + remoteIP
						+ " port=" + remotePORT + " sessionID=" + sessionID);
			}
			sizeRight -= nRead;
			offset += nRead;
		}

		return true;
	}
	
	public void writeRequest(int msgLength) throws IOException {
		//build msg header;
		headerBuff[0] = (byte)((MSG_HEADER >> 24) & 0xFF);
		headerBuff[1] = (byte)((MSG_HEADER >> 16) & 0xFF);
		headerBuff[2] = (byte)((MSG_HEADER >> 8) & 0xFF);
		headerBuff[3] = (byte)((MSG_HEADER) & 0xFF);
		headerBuff[4] = (byte)((msgLength >> 8) & 0xFF);
		headerBuff[5] = (byte)((msgLength) & 0xFF);
		
		// 发送查询请求
		out.write(this.headerBuff, 0, this.headerBuff.length);
		out.write(this.sendBuff, 0, msgLength);
		out.flush();
	}
	
	public int readResponse(boolean ignoreTimeOut) throws IOException {
		// 先读取出消息头
		if (!safeRead(this.headerBuff, 0, this.headerBuff.length, ignoreTimeOut)) {
			return -1;
		}
		
		// 判断一下消息头是否正确
		int msgHeader = ((headerBuff[0] & 0xFF) << 24)
				| ((headerBuff[1] & 0xFF) << 16)
				| ((headerBuff[2] & 0xFF) << 8)
				| ((headerBuff[3] & 0xFF));

		if (msgHeader != MSG_HEADER) {
			log.error("消息头不正确，连接复位. remoteip={} port={} sessionID={}",
					new Object[]{remoteIP, remotePORT, sessionID});
			return -2;
		}
		
		// 读出消息长度
		int msgLength = ((headerBuff[4] & 0xFF) << 8)
				| ((headerBuff[5] & 0xFF));
				
		// 读取消息内容到recvBuff
		if (safeRead(this.recvBuff, 0, msgLength, ignoreTimeOut))
			return msgLength;
		
		return -3;
	}

	protected void close() {
		boolean bConnection = false;
		if (socket != null) {
			bConnection = socket.isConnected();
		}

		log.debug(
				"关闭连接. connection stat={}.remoteip={} port={} sessionID={} last query serialNo={}",
				new Object[]{bConnection, remoteIP, remotePORT, sessionID, this.querySerialNo});
		if (in != null) {
			try {
				in.close();
			} catch (IOException e) {
			}
			in = null;
		}

		if (out != null) {
			try {
				out.close();
			} catch (IOException e) {
			}
			out = null;
		}

		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				log.warn("Socket 关闭时出错. remoteip={} port={} sessionID={}",
						new Object[]{remoteIP, remotePORT, sessionID}, e);
			} finally {
				socket = null;
			}
		}
	}
}
