package cn.uway.igp.lte.parser.pm.pt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.igp.lte.templet.xml.PtPmCmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class PtPmTXTParser extends FileParser {

	/**
	 * 单字符串分割器 @ 2014-7-1
	 */
	public static class FieldSigleTokenSpliter {

		private byte[] srcTxtBytes;

		private int srcTxtLength;

		private byte splitToken;

		private int pos = 0;

		private Charset charset = Charset.defaultCharset();

		public FieldSigleTokenSpliter(byte[] srcTxtBytes, int srcTxtLength, char splitToken) {
			init(srcTxtBytes, srcTxtLength, splitToken);
		}

		public FieldSigleTokenSpliter(String srcTxt, char splitToken) {
			byte[] srcTxtBytes = srcTxt.getBytes();
			init(srcTxtBytes, srcTxtBytes.length, splitToken);
		}

		public void init(byte[] srcTxtBytes, int srcTxtLength, char splitToken) {
			this.srcTxtBytes = srcTxtBytes;
			this.srcTxtLength = srcTxtLength;
			this.splitToken = (byte) splitToken;
			this.pos = 0;
		}

		public void setPos(int pos) {
			this.pos = pos;
		}

		public void setCharSet(String charset) {
			this.charset = Charset.forName(charset);
		}

		public String nextField() {
			String field = null;

			int i = pos;
			while (i < srcTxtLength) {
				if (srcTxtBytes[i] == splitToken)
					break;

				++i;
			}

			// 找到了下一个分割点
			if (i < srcTxtLength) {
				field = new String(srcTxtBytes, pos, (i - pos), charset);
				pos = i + 1;
			} else if (i > pos) {
				field = new String(srcTxtBytes, pos, (i - pos), charset);
				pos = i;
			}

			return field;
		}
	}

	private static Logger LOGGER = LoggerFactory.getLogger(PtPmTXTParser.class);

	public static String myName = "普天性能解析(TXT)";

	/** 输入流 */
	public InputStream rawFileStream = null;

	/** 输入流(ZIP), 当输入流是ZIP文件格式时，该流有效 */
	public ZipInputStream zipstream = null;

	/** ZIP包中的子文件，和ZIP流同时存在 */
	public ZipEntry entry = null;

	/** 当前解码的文件名 */
	public String entryFileName = null;

	/** 数据记录map */
	public Map<String, String> dataRecordMap = new HashMap<String, String>();

	/** 对象类型 */
	public String objectType;

	/** 去重防止key重复set */
	private Set<String> tableKeyset = new HashSet<String>();

	/**
	 * ENODBID 取值方法：从文件名“eNodeB33587502201406050845.txt”提取，其中335875即是ENODBID；
	 */
	public String ENODBID;

	public PtPmTXTParser() {
	}

	public PtPmTXTParser(String tmpfilename) {
		super(tmpfilename);
	}

	/** 字段列表 **/
	protected final static int DEF_FIELD_LENGTH = 256;

	protected String[] fieldsName = new String[DEF_FIELD_LENGTH];

	// 当前数据行缓冲区
	private byte[] currLineBytes = new byte[4 * 1024];

	private int currLineLength = 0;

	// 读前从ftp下载的数据流缓存区
	private byte[] cacheReadBuff = new byte[4 * 1024];

	private int cacheReadBuffOffset = 0;

	private int cacheReadBuffSize = 0;

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();
		
		tableKeyset.clear();
		this.ENODBID = null;
		this.objectType = null;

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 如果是ZIP压缩包，则可能是解多个文件，所以要用ZipInputStream;
		if (accessOutObject.getRawAccessName().toLowerCase().endsWith(".zip")) {
			this.zipstream = new ZipInputStream(inputStream);
			entry = zipstream.getNextEntry();
			if (entry == null)
				return;

			this.extractCommFieldByFileName(entry.getName());
			this.rawFileStream = zipstream;
		} else {
			if (accessOutObject.getRawAccessName().toLowerCase().endsWith(".gz")) {
				//　去掉一层扩展名
				this.extractCommFieldByFileName(FileUtil.getFileName(accessOutObject.getRawAccessName()));
				this.rawFileStream = (new GZIPInputStream(inputStream));
			} else {
				this.extractCommFieldByFileName(accessOutObject.getRawAccessName());
				this.rawFileStream = inputStream;
			}
		}

		this.templet = null;
		this.objectType = null;
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		dataRecordMap.clear();

		do {
			if (extractNextRecord())
				return true;

			// 如果是ZIP文件，则还需要查看下有没有其它的压缩文件entry
			if (zipstream != null) {
				entry = zipstream.getNextEntry();
				if (entry != null) {
					this.extractCommFieldByFileName(entry.getName());
					continue;
				}
			}

			break;
		} while (true);

		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = this.templet.getFieldList();
		Map<String, String> map = this.createExportPropertyMap(this.templet.getDataType());
		for (Field field : fieldList) {
			if (field == null)
				continue;

			String value = dataRecordMap.get(field.getName());
			// String value = dataRecordMap.remove(field.getName());
			if (value == null)
				continue;

			map.put(field.getIndex(), value);
		}

		// 特殊字段处理
		/**
		 * <pre>
		 * 归属网元关键字：EUtranCellRelation=1.335875.2，
		 * 拆分为：
		 * 		LOCALCELLID=1，EutranRelationenb=335875，EutranRelationcell=2
		 * </pre>
		 */
		if (this.objectType.equalsIgnoreCase("2.1.2.5.2.6") 
				|| this.objectType.equalsIgnoreCase("2.1.2.5.2.57")) {
			String eutranCellRelation = dataRecordMap.get("#");
			if (eutranCellRelation != null) {
				String[] subValue = eutranCellRelation.split("\\.");

				if (subValue.length > 0)
					map.put("LOCALCELLID", subValue[0]);

				if (subValue.length > 1)
					map.put("EUTRANRELATIONENB", subValue[1]);

				if (subValue.length > 2)
					map.put("EUTRANRELATIONCELL", subValue[2]);
			}
		}

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		map.put("STAMPTIME", TimeUtil.getDateString(this.currentDataTime));
		map.put("ENODBID", String.valueOf(this.ENODBID));
		record.setType(this.templet.getDataType());
		record.setRecord(map);

		// 清空当前记录数据缓冲区
		dataRecordMap.clear();
		
		return record;
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});
	}

	/**
	 * 找到当前对应的Templet
	 */
	public final boolean findMyTemplet(String objectType) {
		this.templet = this.templetMap.get(objectType);// 这里的key全部转为大写字母
		if (this.templet == null) {
			return false;
		}
		return true;
	}

	public boolean extractNextRecord() throws IOException {
		while (readNextLine()) {
			if (currLineLength < 1)
				continue;

			// 行首
			if (currLineBytes[0] == '#') {
				// 清空Key列表，
				tableKeyset.clear();
				// 解析表头
				processFieldHeader();
				continue;
			}

			// 如果没有对应的解析模板，下面的行要忽略掉，直到下一个有效的数据为止。
			if (objectType == null || this.templet == null)
				continue;

			// 处理行
			FieldSigleTokenSpliter spliter = new FieldSigleTokenSpliter(this.currLineBytes, this.currLineLength, '\t');
			String fieldValue = null;
			int fieldIndex = 0;
			int nValidFieldCount = 0;
			while ((fieldValue = spliter.nextField()) != null) {
				String fileName = this.fieldsName[fieldIndex++];
				if (fieldValue != null && fieldValue.length() > 0) {
					// 防止key重复，导致入库主键冲突。
					if (fieldIndex == 1 && fileName.equals("#")) {
						if (tableKeyset.contains(fieldValue)) {
							dataRecordMap.clear();
							nValidFieldCount = 0;

							LOGGER.warn("文件:\"{}\", objectType=\"{}\", key=\"{}\", 主键重复，该行被忽略.", new Object[]{this.entryFileName, this.objectType,
									fieldValue});

							break;
						}
						tableKeyset.add(fieldValue);
					}

					dataRecordMap.put(fileName, fieldValue);
					++nValidFieldCount;
				}
			}

			// 如果是一个无效的行，则跳过(小于2个有效字段，因为其中一个字段是#号)
			if (nValidFieldCount < 2)
				continue;

			return true;
		}

		return false;
	}

	/**
	 * 解析文件名时间
	 * 
	 * @throws Exception
	 */
	public void extractCommFieldByFileName(String fileEntryName) {
		try {
			this.tableKeyset.clear();
			this.ENODBID = null;
			this.currentDataTime = null;
			this.cacheReadBuffOffset = 0;
			this.cacheReadBuffOffset = 0;
			this.objectType = null;
			this.templates = null;
			
			// eNodeB33587502201406050845.txt
			this.entryFileName = fileEntryName;
			String fileKeyName = FileUtil.getFileName(fileEntryName);
			int nDotPos = fileKeyName.indexOf(".");
			if (nDotPos >= 0) {
				fileKeyName = fileKeyName.substring(0, nDotPos);
			}
			
			if (fileKeyName.length() >= 12) {
				this.currentDataTime = TimeUtil.getyyyyMMddHHmmDate(fileKeyName.substring(fileKeyName.length() - 12, fileKeyName.length()));
				// 文件名是结束时间，减去一个文件粒度的偏移时间
				final long fileOffsetTime = 15*60*1000 /*+ 3*30*24*60*60*1000L*/;
				this.currentDataTime = new Date(this.currentDataTime.getTime() - fileOffsetTime);
				//this.currentDataTime = new Date(this.currentDataTime.getTime() - (31*24*60*60*1000L));
			}

			String pattern = StringUtil.getPattern(fileKeyName, "\\d{6}");
			if (pattern != null) {
				this.ENODBID = pattern;
			}

		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		PtPmCmXmlTempletParser templetParser = new PtPmCmXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		this.templetMap = templetParser.getTemplets();
	}

	/**
	 * 读取TXT的下一行
	 * 
	 * @return
	 * @throws IOException
	 */
	public boolean readNextLine() throws IOException {
		int currLineOffset = 0;
		currLineLength = 0;
		byte currByte = '\0';
		while (true) {
			int nCacheSizeRight = cacheReadBuffSize - cacheReadBuffOffset;
			int nLength = 0;
			while (nCacheSizeRight > 0) {
				--nCacheSizeRight;

				// 遇到换行符，则返回
				currByte = cacheReadBuff[cacheReadBuffOffset + nLength];
				if (currByte == '\n' || currByte == '\r') {
					// 忽略掉空行
					if (currLineLength < 1 && nLength < 1) {
						++cacheReadBuffOffset;
						continue;
					}

					if (nLength > 0) {
						safeArrayCopyToCurrLineBytes(cacheReadBuff, cacheReadBuffOffset, currLineOffset, nLength);
						currLineLength += nLength;
						cacheReadBuffOffset += nLength;
					}

					// 跳过当前的换行符
					++cacheReadBuffOffset;

					return true;
				}

				++nLength;
			}

			// 已读完缓冲区，但剩下的字符未出现换行符，将剩下的字符复制到currLine中。
			if (nLength > 0) {
				safeArrayCopyToCurrLineBytes(cacheReadBuff, cacheReadBuffOffset, currLineLength, nLength);
				currLineOffset += nLength;
				currLineLength += nLength;
			}

			// 继续读文件到缓冲区中
			cacheReadBuffOffset = 0;
			cacheReadBuffSize = rawFileStream.read(cacheReadBuff, 0, cacheReadBuff.length);
			// 文件读结束，将最后残存的数据(如有)作为一行返回。
			if (cacheReadBuffSize <= 0) {
				if (currLineLength > 0)
					return true;

				break;
			}
		}

		return false;
	}

	/**
	 * 处理行首，获得数据column的栏位名，解析模板名
	 */
	public void processFieldHeader() {
		this.templet = null;
		this.objectType = null;

		FieldSigleTokenSpliter spliter = new FieldSigleTokenSpliter(this.currLineBytes, this.currLineLength, '\t');
		String fieldValue = null;
		int fieldIndex = 0;
		while ((fieldValue = spliter.nextField()) != null) {
			ensureCapacity(fieldIndex);
			fieldsName[fieldIndex] = fieldValue;

			// 找到第6个"."，在第6个"."前面为objectType
			if (objectType == null && fieldsName[fieldIndex] != null && fieldsName[fieldIndex].length() > 0) {
				int dotCount = 0;
				int preDotPos = 0;
				while (dotCount < 6) {
					int currDotPos = fieldsName[fieldIndex].indexOf('.', preDotPos);
					if (currDotPos < 0)
						break;

					preDotPos = currDotPos + 1;
					++dotCount;
				}

				if (dotCount == 6) {
					objectType = fieldsName[fieldIndex].substring(0, preDotPos - 1);
				}
			}

			++fieldIndex;
		}

		// 找到对应的模板
		if (objectType != null && objectType.length() > 0) {
			if (!findMyTemplet(objectType)) {
				LOGGER.info("找不到数据类型:{}对应的解析模板，将忽略该文件的采集.文件名:{}", this.objectType, this.accessOutObject.getRawAccessName());
			}
		}
	}

	/**
	 * 确保复制到currLineBytes时不越界
	 * 
	 * @param src
	 *            源byte[]数据组
	 * @param offsetSrc
	 *            从源数组中的哪个位置开始复制
	 * @param offsetDst
	 *            复制到currLineBytes哪个位置
	 * @param length
	 *            复制的长度
	 */
	public void safeArrayCopyToCurrLineBytes(byte[] src, int offsetSrc, int offsetDst, int length) {
		// +1是为了预留一个行尾加入的'\t'字符
		int newCapacity = offsetDst + length/* + 1 */;
		if (this.currLineBytes.length < newCapacity) {
			// 空间不够时，每次多扩容1K空间。
			newCapacity += 1024;
			this.currLineBytes = Arrays.copyOf(this.currLineBytes, newCapacity);
		}

		try {
			System.arraycopy(src, offsetSrc, currLineBytes, offsetDst, length);
		} catch (Exception e) {
			assert (false);
		}
	}

	/**
	 * 确保字段索引在xmlFieldsName中能存放得下，存不了，则扩容数组
	 * 
	 * @param index
	 */
	public void ensureCapacity(int index) {
		if (index < 0)
			throw new IndexOutOfBoundsException("out of bound. index=" + index);

		if (index >= fieldsName.length) {
			int newCapacity = (fieldsName.length * 3) / 2 + 1;
			if (newCapacity <= index) {
				newCapacity = index + 1;
			}

			// minCapacity is usually close to size, so this is a win:
			fieldsName = Arrays.copyOf(fieldsName, newCapacity);
		}
	}
}
