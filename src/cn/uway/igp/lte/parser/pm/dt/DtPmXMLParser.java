package cn.uway.igp.lte.parser.pm.dt;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.igp.lte.templet.xml.DtPmCmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class DtPmXMLParser extends FileParser {

	private static Logger LOGGER = LoggerFactory.getLogger(DtPmXMLParser.class);

	public static String myName = "大唐性能解析(XML)";

	/** 版本 */
	public String version;

	/** 输入流(ZIP), 当输入流是ZIP文件格式时，该流有效 */
	public ZipInputStream zipstream = null;

	/** ZIP包中的子文件，和ZIP流同时存在 */
	public ZipEntry entry = null;

	/** 当前解码的文件名 */
	public String entryFileName = null;

	/** 输入流 */
	public InputStream rawFileStream;

	/** XML流 */
	public XMLStreamReader reader = null;

	/** 数据记录map */
	public Map<String, String> dataRecordMap = new HashMap<String, String>();

	/** 对象类型 */
	public String objectType;

	public DtPmXMLParser() {
	}

	public DtPmXMLParser(String tmpfilename) {
		super(tmpfilename);
	}

	/** 字段列表 **/
	protected final static int DEF_FIELD_LENGTH = 256;

	protected String[] xmlFieldsName = new String[DEF_FIELD_LENGTH];

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();

		this.version = null;
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

		// 复位公共信息参数
		for (int i = 0; i < xmlFieldsName.length; ++i) {
			xmlFieldsName[i] = null;
		}
		this.objectType = null;
		this.templet = null;
		// this.currentDataTime = null;
		this.entryFileName = null;

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
			if (value == null)
				continue;
			// 先加判下长度(4就是"null"字符串的长度)，会提高效率。
			if (value.length() == 4 && value.equalsIgnoreCase("null"))
				continue;

			map.put(field.getIndex(), value);
		}
		dataRecordMap.clear();

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		map.put("STAMPTIME", TimeUtil.getDateString(this.currentDataTime));
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
		return true;
	}

	/**
	 * 解析文件名时间
	 * 
	 * @throws Exception
	 */
	public void extractCommFieldByFileName(String fileEntryName) {
		try {
			String fileName = FileUtil.getFileName(fileEntryName);
			this.entryFileName = fileName;
			String patternTime = StringUtil.getPattern(fileName, "[-]\\d{8}[-]\\d{4}");
			if (patternTime != null) {
				// 先从文件名是截取一个默认的时间, 真正最后入库的还是从原始文件解析出来的时间.
				this.currentDataTime = TimeUtil.getyyyyMMddHorizontalLineHHmmDate(patternTime.substring(1));
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
		DtPmCmXmlTempletParser templetParser = new DtPmCmXmlTempletParser();
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
		this.dataRecordMap.clear();

		// 标签名称
		String elTagName;

		// 标签类型
		int elType = -1;

		// 字段名列表标识
		boolean fieldsNameSetFlag = false;
		// 记录解析标识
		boolean recordParseSetFlag = false;
		// SN标识
		boolean snKeySetFlag = false;

		// 子节点名称
		String subFieldName = null;

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

				// 以下代码的解析，按在xml出现的频率，频率高的在上，频率低写在下面，所以要倒着看
				switch (elType) {
					case XMLStreamConstants.START_ELEMENT : {
						// 处理字段名列表
						{
							if (recordParseSetFlag) {
								if ("V".equalsIgnoreCase(elTagName)) {
									int index = Integer.parseInt(StringUtil.nvl(reader.getAttributeValue(null, "i"), "-1"));
									if (index < 0 || index >= xmlFieldsName.length || xmlFieldsName[index] == null)
										continue;
									String value = reader.getElementText();

									dataRecordMap.put(xmlFieldsName[index], value);
								} else if ("SN".equalsIgnoreCase(elTagName)) {
									subFieldName = reader.getElementText().trim().toUpperCase();
									snKeySetFlag = true;
								} else if (snKeySetFlag && subFieldName != null && subFieldName.length() > 0 && "SV".equalsIgnoreCase(elTagName)) {
									String value = reader.getElementText();
									dataRecordMap.put(subFieldName, value);
									subFieldName = null;
									snKeySetFlag = false;
								}

								continue;
							}

							if ("Pm".equalsIgnoreCase(elTagName)) {
								// 解析Dn
								String dn = reader.getAttributeValue(null, "Dn");
								if (dn != null && dn.length() > 0) {
									dn = dn.trim();
									dataRecordMap.put("DN", dn);;
									// Dn="DATANGMOBILE-anhuidianxin-hefei,SubNetwork=1,ManagedElement=11000000433314,EnbFunction=433314,EutranCellTdd=2"
									int nCurrentCommaTokenPos = 0;
									int nPreCommaTokenPos = nCurrentCommaTokenPos;
									while (nPreCommaTokenPos < dn.length()) {
										nCurrentCommaTokenPos = dn.indexOf(',', nPreCommaTokenPos);
										String subEntry = null;
										if (nCurrentCommaTokenPos < 0) {
											if (dn.length() > nPreCommaTokenPos) {
												subEntry = dn.substring(nPreCommaTokenPos, dn.length()).trim();
												nPreCommaTokenPos = dn.length();
											} else {
												break;
											}
										} else {
											subEntry = dn.substring(nPreCommaTokenPos, nCurrentCommaTokenPos).trim();
											nPreCommaTokenPos = nCurrentCommaTokenPos + 1;
										}

										int equalsTokenPos = subEntry.indexOf('=');
										if (equalsTokenPos > 0 && equalsTokenPos < subEntry.length()) {
											String subEntryName = "DN." + subEntry.substring(0, equalsTokenPos).trim().toUpperCase();
											String subEntryValue = subEntry.substring(equalsTokenPos + 1).trim();

											if (subEntryName.length() > 0 && subEntryValue.length() > 0) {
												dataRecordMap.put(subEntryName, subEntryValue);
											}
										}
									}
								}

								// 解析UserLabel
								String UserLabel = reader.getAttributeValue(null, "UserLabel");
								if (UserLabel != null) {
									dataRecordMap.put("USERLABEL", UserLabel.trim());
								}

								recordParseSetFlag = true;
								continue;
							}
						}

						// 处理字段名列表
						{
							if (fieldsNameSetFlag && "N".equalsIgnoreCase(elTagName)) {
								int index = Integer.parseInt(StringUtil.nvl(reader.getAttributeValue(null, "i"), "-1"));
								if (index < 0)
									continue;

								String nodeName = StringUtil.nvl(reader.getElementText(), "");
								ensureCapacity(index);
								xmlFieldsName[index] = nodeName.toUpperCase();

								continue;
							}

							if ("PmName".equalsIgnoreCase(elTagName)) {
								fieldsNameSetFlag = true;
								continue;
							}
						}

						if ("ObjectType".equalsIgnoreCase(elTagName)) {
							this.objectType = StringUtil.nvl(reader.getElementText(), "").toUpperCase();
							if (!findMyTemplet(objectType)) {
								LOGGER.info("找不到数据类型:{}对应的解析模板，将忽略该文件的采集.文件名:{}", this.objectType, this.accessOutObject.getRawAccessName());
								return false;
							}

							continue;
						}

						// 解析文件开始时间
						if ("BeginTime".equalsIgnoreCase(elTagName)) {
							String timeStr = StringUtil.nvl(reader.getElementText(), "");
							this.currentDataTime = TimeUtil.getDate(timeStr.replace('T', ' ').substring(0, (timeStr.indexOf("+"))));
						}

						break;
					}
					case XMLStreamConstants.END_ELEMENT : {
						if (snKeySetFlag && "SN".equalsIgnoreCase(elTagName)) {
							snKeySetFlag = false;
							continue;
						}

						if ("Pm".equalsIgnoreCase(elTagName)) {
							recordParseSetFlag = false;
							// 如果有对应的模板，且有数据，则返回true,否则，继续解
							if (this.templet != null && this.dataRecordMap.size() > 0) {
								return true;
							} else {
								this.dataRecordMap.clear();
							}
						}

						if ("PmName".equalsIgnoreCase(elTagName)) {
							fieldsNameSetFlag = false;
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

	/**
	 * 确保字段索引在xmlFieldsName中能存放得下，存不了，则扩容数组
	 * 
	 * @param index
	 */
	public void ensureCapacity(int index) {
		if (index < 0)
			throw new IndexOutOfBoundsException("out of bound. index=" + index);

		if (index >= xmlFieldsName.length) {
			int newCapacity = (xmlFieldsName.length * 3) / 2 + 1;
			if (newCapacity <= index) {
				newCapacity = index + 1;
			}

			// minCapacity is usually close to size, so this is a win:
			xmlFieldsName = Arrays.copyOf(xmlFieldsName, newCapacity);
		}
	}
}
