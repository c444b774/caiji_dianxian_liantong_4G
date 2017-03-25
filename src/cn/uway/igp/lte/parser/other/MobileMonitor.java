package cn.uway.igp.lte.parser.other;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.igp.lte.parser.cm.DtCmXMLParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;


public class MobileMonitor extends FileParser {
	private class MobileMonitorXmlTempletParser extends TempletParser {
		/**
		 * 处理file属性
		 */
		public void personalHandler(Templet templet, Node templetNode) throws Exception {
			Node measurementTypeNode = templetNode.getAttributes().getNamedItem("ObjectType");
			if (measurementTypeNode != null) {
				String measurementTypeName = measurementTypeNode.getNodeValue();
				if (StringUtil.isEmpty(measurementTypeName))
					throw new Exception("ObjectType属性值不能为空");
				templet.setDataName(measurementTypeName.trim());
			} else
				throw new Exception("缺少ObjectType属性");
		}
	}
	
	
	private static Logger LOGGER = LoggerFactory.getLogger(DtCmXMLParser.class);

	public static String myName = "移动安全态势解析(XML)";

	protected static String fileKeyTag = "_YCAQ_";
	
	/** 输入流(ZIP), 当输入流是ZIP文件格式时，该流有效 */
	protected ZipInputStream zipstream = null;

	/** ZIP包中的子文件，和ZIP流同时存在 */
	protected ZipEntry entry = null;

	/** 当前解码的文件名 */
	protected String entryFileName = null;

	/** 输入流 */
	protected InputStream rawFileStream;

	/** XML流 */
	protected XMLStreamReader reader = null;

	/** 公共数据记录map */
	protected Map<String, String> commDataRecordMap = new HashMap<String, String>();
	
	/** 数据记录map */
	protected Map<String, String> dataRecordMap = new HashMap<String, String>();
	
	protected Set<String> attrSet = new HashSet<String>();

	/** 对象类型 */
	protected String objectType;
	
	/** 上一个xml节点名称 */
	private String prevElementName;
	
	/** xml 的element节点层级 */
	private int nElementHierarchy = -1;
	
	/** 记录标识名 */
	private String recordTagElementName;

	public MobileMonitor() {
		
	}
	
	public MobileMonitor(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();

		this.templet = null;
		this.objectType = null;

		this.zipstream = null;
		this.entry = null;
		this.entryFileName = null;

		// 解析模板 获取当前文件对应的templet
		parseTemplet();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		// 如果是ZIP压缩包，则可能是解多个文件，所以要用ZipInputStream;
		if (accessOutObject.getRawAccessName().toLowerCase().endsWith(".zip")) {
			this.zipstream = new ZipInputStream(inputStream);
			entry = zipstream.getNextEntry();
			if (entry == null)
				return;

			this.rawFileStream = zipstream;
			LOGGER.debug("开始解析子文件:{}, ZIP文件：{}", entry.getName(), this.rawName);
			extractCommFieldByFileName(entry.getName());
		} else if (accessOutObject.getRawAccessName().toLowerCase().endsWith(".gz")) {
			this.rawFileStream = new GZIPInputStream(inputStream);
			extractCommFieldByFileName(accessOutObject.getRawAccessName());
		} else {
			this.rawFileStream = inputStream;
			extractCommFieldByFileName(accessOutObject.getRawAccessName());
		}

		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		this.reader = fac.createXMLStreamReader(this.rawFileStream);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		do {
			if (extractNextRecord())
				return true;

			if (prepareNextZipFileEntry())
				continue;

			break;
		} while (true);

		return false;
	}

	protected boolean prepareNextZipFileEntry() throws IOException, XMLStreamException {
		if (this.zipstream == null)
			return false;

		this.objectType = null;
		this.templet = null;
		this.nElementHierarchy = -1;
		this.entryFileName = null;
		this.commDataRecordMap.clear();
		this.attrSet.clear();

		this.entry = zipstream.getNextEntry();
		if (entry != null) {
			// 从文件名中提取公共字段信息
			extractCommFieldByFileName(entry.getName());

			// 设置xml解析器reader
			XMLInputFactory fac = XMLInputFactory.newInstance();
			fac.setProperty("javax.xml.stream.supportDTD", false);
			this.reader = fac.createXMLStreamReader(this.rawFileStream);
			LOGGER.debug("开始解析子文件:{}, ZIP文件：{}", entry.getName(), this.rawName);

			return true;
		}

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

			// String value = dataRecordMap.get(field.getName());
			String value = dataRecordMap.remove(field.getName());
			if (value == null) {
				value = this.commDataRecordMap.get(field.getName());
			}
			
			if (value == null)
				continue;
			
			// 先加判下长度(4就是"null"字符串的长度)，会提高效率。
			if (value.length() == 4 && value.equalsIgnoreCase("null"))
				continue;
			
			//转换日期
			if (field.getName().indexOf("TIME")>=0 && value.length()>=14) {
				try {
					Date date = TimeUtil.getyyyyMMddHHmmDate(value);
					value = TimeUtil.getDateString(date);
				} catch  (Exception e) {}
				
			}
			
			map.put(field.getIndex(), value);
		}
		dataRecordMap.clear();

		// 公共回填字段
		map.put("mmeid", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("collecttime", TimeUtil.getDateString(new Date()));
		map.put("stamptime", TimeUtil.getDateString(this.currentDataTime));
		record.setType(this.templet.getDataType());
		record.setRecord(map);

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
		
		List<Field> fieldList = this.templet.getFieldList();
		for (Field field : fieldList) {
			if (field == null)
				continue;
			attrSet.add(field.getName());
		}
		
		return true;
	}

	/**
	 * 解析文件名时间
	 * 
	 * @throws Exception
	 */
	public void extractCommFieldByFileName(String fileEntryName) {
		try {
			this.objectType = null;
			this.templet = null;
			this.nElementHierarchy = -1;
			this.commDataRecordMap.clear();
			this.attrSet.clear();
			
			// I_YCAQ_STAT_001_001_201404120000_98776658_DAY.xml
			String fileName = FileUtil.getFileName(fileEntryName);
			this.entryFileName = fileName;
			
			// 找出文件的类型
			fileName = fileName.toUpperCase();
			int nTagBeginPos = fileName.indexOf(fileKeyTag);
			if (nTagBeginPos > 0) {
				++nTagBeginPos;
				
				int nTagEndPos = fileName.indexOf( "_", nTagBeginPos + fileKeyTag.length());
				if (nTagEndPos > nTagBeginPos) {
					this.objectType = fileName.substring(nTagBeginPos, nTagEndPos);
					if (!findMyTemplet(this.objectType)) {
						LOGGER.debug("无法找到匹配的解析模板．fileName={}", fileName);
						return;
					}
				}
			}
			
			if (this.objectType == null) {
				LOGGER.debug("无法识别文件的类型．fileName={}", fileName);
				return;
			}
			
			String patternTime = StringUtil.getPattern(fileName, "[_]20\\d{10}[_]");
			if (patternTime != null) {
				// 先从文件名是截取一个默认的时间, 真正最后入库的还是从原始文件解析出来的时间.
				this.currentDataTime = TimeUtil.getyyyyMMddHHmmDate(patternTime.substring(1));
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
		MobileMonitorXmlTempletParser templetParser = new MobileMonitorXmlTempletParser();
		templetParser.tempfilepath = templates;
		templetParser.parseTemp();
		this.templetMap = templetParser.getTemplets();
	}

	/**
	 * 从当前的entry中，提取下一条记录
	 * 
	 * @return
	 * @throws XMLStreamException
	 * @throws ParseException
	 */
	protected boolean extractNextRecord() throws XMLStreamException, ParseException {
		if (this.objectType == null || this.templet == null)
			return false;
		
		this.dataRecordMap.clear();

		// 标签名称
		String elTagName;
		// 标签类型
		int elType = -1;
		// 记录解析标识
		boolean recordParseSetFlag = false;
		
		try {
			while (reader.hasNext()) {
				elTagName = null;
				try {
					elType = reader.next();
				} catch (Exception e) {
					continue;
				}
				// 只取开始和结束标签
				if (elType != XMLStreamConstants.START_ELEMENT && elType != XMLStreamConstants.END_ELEMENT)
					continue;
				elTagName = reader.getLocalName();
				String elUpperTagName = elTagName.toUpperCase();
				// 以下代码的解析，按在xml出现的频率，频率高的在上，频率低写在下面，所以要倒着看
				switch (elType) {
					case XMLStreamConstants.START_ELEMENT : {
						++nElementHierarchy;
						if (recordTagElementName == null) {
							if (nElementHierarchy == 2
								&& prevElementName != null 
								&& prevElementName.equalsIgnoreCase(elTagName + "s") ){
								// 找出文件中的记录标识符，并寻找记录开始标识．
								recordTagElementName = elTagName;
								recordParseSetFlag = true;
							} else {
								// 找出公共字段的属性，放到commDataRecordMap中
								if (nElementHierarchy == 1 && attrSet.contains(elUpperTagName) ) {
									String value = reader.getElementText();
									--nElementHierarchy;
									if (value != null) {
										this.commDataRecordMap.put(elUpperTagName, value);
									}
								}
								
								prevElementName = elTagName;
							}
							
							continue;
						} else if (!recordParseSetFlag && recordTagElementName.equals(elTagName)) {
							// 寻找记录开始标识
							recordParseSetFlag = true;
							continue;
						}
						
						if (recordParseSetFlag && nElementHierarchy == 3) {
							String value = reader.getElementText();
							--nElementHierarchy;
							dataRecordMap.put(elUpperTagName, value);

							continue;
						}

						break;
					}
					case XMLStreamConstants.END_ELEMENT : {
						--nElementHierarchy;
						if (recordParseSetFlag && recordTagElementName.equalsIgnoreCase(elTagName)) {
							recordParseSetFlag = false;
							return true;
						}
						
						break;
					}
					default :
						break;
				}
			}
		} catch (XMLStreamException e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}

		return false;
	}

}
