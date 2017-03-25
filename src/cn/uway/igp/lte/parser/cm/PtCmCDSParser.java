package cn.uway.igp.lte.parser.cm;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.FileParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.igp.lte.parser.pm.pt.PtPmTXTParser.FieldSigleTokenSpliter;
import cn.uway.igp.lte.templet.xml.PtPmCmXmlTempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class PtCmCDSParser extends FileParser {

	/**
	 * 解析字段
	 */
	public static class PTCmParseField {

		public String name;

		public String index;

		public String[] groupKeyField;

		/**
		 * 生成Key
		 * 
		 * @param node
		 * @return
		 */
		public String buildCDSNodeGroupKey(CDSNode node) {

			FieldSigleTokenSpliter spliter = new FieldSigleTokenSpliter(node.key, '.');
			spliter.setPos(name.length() + 1);

			StringBuilder sb = new StringBuilder();
			int i = 0;
			String nextFieldValue = null;
			while ((nextFieldValue = spliter.nextField()) != null && i < groupKeyField.length) {
				if (groupKeyField[i] != null && groupKeyField[i].length() > 0) {
					if (sb.length() > 0)
						sb.append(".");
					sb.append(nextFieldValue);
				}

				++i;
			}

			// 如果在key中解出来的分组个数不一致，在前面补0(现场文件，有的缺少前面的LOCALCELLID)
			for (; i < groupKeyField.length; ++i) {
				if (sb.length() > 0)
					sb.insert(0, "0.");
				else
					sb.insert(0, "0");
			}

			return sb.toString();
		}

		/**
		 * 提Key的各属性值到dataMap中
		 * 
		 * @param node
		 * @param dataMap
		 */
		public void extractCDSNodeGroupKeyValue(CDSNode node, String key, Map<String, String> dataMap) {
			FieldSigleTokenSpliter spliter = new FieldSigleTokenSpliter(key, '.');
			// spliter.setPos(name.length()+1);

			int i = 0;
			String nextFieldValue = null;
			while ((nextFieldValue = spliter.nextField()) != null && i < groupKeyField.length) {
				if (groupKeyField[i] != null && groupKeyField[i].length() > 0) {
					if (nextFieldValue != null) {
						dataMap.put(groupKeyField[i], nextFieldValue);
					}
				}

				++i;
			}
		}
	}

	/**
	 * CDS文件节点
	 */
	public static class CDSNode {

		public String key;

		public String type;

		public String value;

		public CDSNode(String key, String type, String value) {
			this.key = key;
			this.type = type;
			this.value = value;
		}

		public int commare(CDSNode cdsNode) {
			return key.compareTo(cdsNode.key);
		}

		/**
		 * 比较key(因为key都是“数字” + "圆点"组成，所以不需要区分大小写
		 * 
		 * @param key
		 *            键值名称
		 * @param fullComareMode
		 *            是否全值比较，如果全值比较，则key要完全相同，否则，只需key的前半部份相同就可以
		 * @return 0:相同 正数:key>otherKey 负数:key<otherKey
		 */
		public int compareKey(String otherKey, boolean fullComareMode) {
			int ret = key.compareTo(otherKey);
			if (ret == 0)
				return 0;

			int compareKeyLength = otherKey.length();
			if (compareKeyLength < key.length()) {
				// 如果非全值比较，只要key的前部份和compareKeyName完全相同，且key长出字符的下一个是"."，则也视为相同
				if (!fullComareMode) {
					if (key.substring(0, compareKeyLength).compareTo(otherKey) == 0
							&& key.substring(compareKeyLength, compareKeyLength + 1).equals(".")) {
						return 0;
					}
				}
			}

			return ret;
		}
	}

	private static Logger LOGGER = LoggerFactory.getLogger(PtCmCDSParser.class);

	public static String myName = "普天参数解析(CDS)";

	/** 输入流 */
	public InputStream rawFileStream;

	/** 输入流(ZIP), 当输入流是ZIP文件格式时，该流有效 */
	public ZipInputStream zipstream = null;

	/** ZIP包中的子文件，和ZIP流同时存在 */
	public ZipEntry entry = null;

	/** 当前解码的文件名 */
	public String entryFileName = null;

	/**
	 * ENBSYSEQUIPMENTID 取值方法：从文件名“eNodeB100106000001201406031623.cds”提取，其中100106000001即是ENBSYSEQUIPMENTID；
	 */
	public String ENBSYSEQUIPMENTID;

	/** 字段列表 **/
	protected final static int DEF_FIELD_LENGTH = 256;

	protected String[] fieldsName = new String[DEF_FIELD_LENGTH];

	// CDS NODE个数，初始化1000个,按当前的样例文件的经验值
	protected List<CDSNode> cdsNodeList = new ArrayList<CDSNode>(10000);

	// 解析字段模板列表
	protected Map<String, List<PTCmParseField>> parseTemplateFieldEx = new HashMap<String, List<PTCmParseField>>();

	protected List<ParseOutRecord> recordList = null;

	// 当前数据行缓冲区
	private byte[] currLineBytes = new byte[4 * 1024];

	private int currLineLength = 0;

	// 读前从ftp下载的数据流缓存区
	private byte[] cacheReadBuff = new byte[4 * 1024];

	private int cacheReadBuffOffset = 0;

	private int cacheReadBuffSize = 0;

	public PtCmCDSParser() {
	}

	public PtCmCDSParser(String tmpfilename) {
		this.templates = tmpfilename;
	}

	/**
	 * 二分查找cdsNodeList，注意，如果非完整比较，二分查找出来的node，不一定是第一个node
	 * 
	 * @param key
	 * @param beginIndex
	 * @param endIndex
	 * @param fullComareMode
	 *            是否完整比较
	 * @return
	 */
	public int findCDSNodeIndex(String key, int beginIndex, int endIndex, boolean fullComareMode) {
		if (cdsNodeList.size() < 1 || beginIndex < 0 || endIndex >= cdsNodeList.size())
			return -1;

		while (beginIndex <= endIndex) {
			int i = beginIndex + (endIndex - beginIndex) / 2;
			int compareRet = cdsNodeList.get(i).compareKey(key, fullComareMode);
			if (compareRet == 0)
				return i;

			if (compareRet > 0) {
				// 如果中间节点的key比要查找的key大，则在左边查找
				endIndex = i - 1;
			} else {
				// 如果中间节点的key比要查找的key小，则在右边查找
				beginIndex = i + 1;
			}
		}

		return -1;
	}

	// 根据key名，在cdsNodeList找到对应的node
	public CDSNode getCDSNode(String key) {
		if (cdsNodeList == null || cdsNodeList.size() < 1)
			return null;

		int i = findCDSNodeIndex(key, 0, cdsNodeList.size() - 1, true);
		if (i < 0)
			return null;

		return cdsNodeList.get(i);
	}

	// 根据key名，在cdsNodeList找到对应的全半部份key都相同的node列表
	public List<CDSNode> getCDSNodes(String key) {
		int i = findCDSNodeIndex(key, 0, cdsNodeList.size() - 1, false);
		if (i < 0)
			return null;

		// 128，经验值，默认的ArrayList初始化个数
		List<CDSNode> subCdsNodeList = new LinkedList<CDSNode>();

		/**
		 * 因为cdsNodeList
		 */

		// 往前面找
		int frontIndex = i - 1;
		while (frontIndex >= 0) {
			if (cdsNodeList.get(frontIndex).compareKey(key, false) != 0)
				break;

			subCdsNodeList.add(0, cdsNodeList.get(frontIndex));
			--frontIndex;
		}

		// 插入当前找到的node
		subCdsNodeList.add(cdsNodeList.get(i));

		// 往后面找
		int backIndex = i + 1;
		while (backIndex < cdsNodeList.size()) {
			if (cdsNodeList.get(backIndex).compareKey(key, false) != 0)
				break;

			subCdsNodeList.add(cdsNodeList.get(backIndex));
			++backIndex;
		}

		return subCdsNodeList;
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();

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
			extractNextRecordsByFileEntry(entry.getName());
		} else {
			this.rawFileStream = inputStream;
			extractNextRecordsByFileEntry(accessOutObject.getRawAccessName());
		}
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		do {
			if (recordList != null && recordList.size() > 0)
				return true;

			// 如果是ZIP文件，则还需要查看下有没有其它的压缩文件entry
			if (zipstream != null) {
				entry = zipstream.getNextEntry();
				if (entry != null) {
					extractNextRecordsByFileEntry(entry.getName());
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

		ParseOutRecord record = recordList.remove(0);
		Map<String, String> map = record.getRecord();

		// 公共回填字段
		map.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		map.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		map.put("STAMPTIME", TimeUtil.getDateString(this.currentDataTime));
		map.put("ENBSYSEQUIPMENTID", String.valueOf(this.ENBSYSEQUIPMENTID));
		map.put("FILE_NAME", this.entryFileName);

		return record;
	}

	@Override
	public void close() {
		// 标记解析结束时间
		this.endTime = new Date();
		LOGGER.debug("[{}]-{}，处理{}条记录", new Object[]{task.getId(), myName, readLineNum});

		if (recordList != null) {
			recordList.clear();
			recordList = null;
		}
	}

	/**
	 * 解析文件名时间
	 * 
	 * @throws Exception
	 */
	public void extractCommFieldByFileName(String fileEntryName) {
		try {
			// eNodeB100106000001201406031623.cds
			String fileName = FileUtil.getFileName(fileEntryName);
			this.entryFileName = fileName;
			if (fileName.length() >= 12) {
				this.currentDataTime = TimeUtil.getyyyyMMddHHmmDate(fileName.substring(fileName.length() - 12, fileName.length()));

				// 参数的要按天取整
				long timer = this.currentDataTime.getTime();
				// 时区处理(GMT+8)
				timer = timer + (8 * 60 * 60 * 1000L);
				timer = timer - timer % (24 * 60 * 60 * 1000L);
				timer = timer - (8 * 60 * 60 * 1000L);

				this.currentDataTime = new Date(timer);
			}

			String pattern = StringUtil.getPattern(fileName, "\\d{12}");
			if (pattern != null) {
				this.ENBSYSEQUIPMENTID = pattern;
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

		// 对解析模板进行预处理
		parseTemplateFieldEx.clear();
		this.templetMap = templetParser.getTemplets();
		for (Entry<String, Templet> entry : this.templetMap.entrySet()) {
			Templet tp = entry.getValue();
			List<Field> fieldList = tp.getFieldList();
			List<PTCmParseField> ptcmParseFieldList = new ArrayList<PTCmParseField>(fieldList.size());

			for (Field field : fieldList) {
				String name = field.getName();

				List<String> subGroupFieldList = new ArrayList<String>();
				Pattern pattern = Pattern.compile("[\\$][\\{][^\\.]{0,}[\\}]");
				Matcher matcher = pattern.matcher(name);
				while (matcher.find()) {
					String subGroupField = matcher.group();
					if (subGroupField.length() > 3) {
						subGroupField = subGroupField.substring(2, subGroupField.length() - 1);
					} else {
						subGroupField = "";
					}

					subGroupFieldList.add(subGroupField);
				}

				PTCmParseField ptcmField = new PTCmParseField();
				if (subGroupFieldList.size() > 0) {
					int nFirstGroupKeyPos = name.indexOf("${");
					if (nFirstGroupKeyPos > 0) {
						// 减去一个圆点的位置
						nFirstGroupKeyPos -= 1;
					}
					ptcmField.name = name.substring(0, nFirstGroupKeyPos);

					int i = 0;
					ptcmField.groupKeyField = new String[subGroupFieldList.size()];
					for (String groupKeyName : subGroupFieldList) {
						ptcmField.groupKeyField[i++] = groupKeyName;
					}
				} else {
					ptcmField.name = name;
				}
				ptcmField.index = field.getIndex();
				ptcmParseFieldList.add(ptcmField);
			}

			parseTemplateFieldEx.put(entry.getKey(), ptcmParseFieldList);
		}
	}

	/**
	 * 解析CDS文件，将解析好的记录，存放到recordList中
	 * 
	 * @throws IOException
	 */
	protected void extractNextRecordsByFileEntry(String fileEntryName) throws IOException {
		this.extractCommFieldByFileName(fileEntryName);

		// 清除残留数据
		if (this.recordList == null) {
			this.recordList = new LinkedList<ParseOutRecord>();
		} else {
			this.recordList.clear();
		}

		this.cdsNodeList.clear();

		// 读取CDS文件的entry到cdsNodeList列表
		parseCDSFileEntryToCDSNodeList();
		if (this.cdsNodeList.size() < 1)
			return;

		// TODO:test
		// if (this.getCDSNode("2.1.2.1.1.1.0").value.equals("358405")) {
		// LOGGER.warn("!!!: ENBFUNCTION=358405 fileName={}", fileEntryName);
		// }

		// 创建记录
		buildOutRecordFromCdsNodeList();
	}

	/**
	 * 将CDS的文件条目entry，解析读取到cdsNodeList列表
	 * 
	 * @throws IOException
	 */
	private void parseCDSFileEntryToCDSNodeList() throws IOException {
		// 将源文件中的所有键值都读取到cdsNodeList列表中
		FieldSigleTokenSpliter spliter = new FieldSigleTokenSpliter(null, 0, ':');
		// TODO:普天文件默认编码为GBK
		spliter.setCharSet("GBK");

		while (readNextLine()) {
			if (currLineLength < 1)
				continue;

			// 忽略注释行
			if (currLineBytes[0] == '#') {
				continue;
			}

			// 处理行
			/**
			 * <pre>
			 * 	2.1.2.1.5.5.3.1.1.22.1.2.335872.460.1.2:I:1
			 * 	field[0]:name
			 * 	field[1]:type
			 * 	field[2]:value
			 * </pre>
			 */
			spliter.init(this.currLineBytes, this.currLineLength, ':');
			String field[] = new String[3];
			String fieldValue = null;
			int fieldIndex = 0;
			while ((fieldValue = spliter.nextField()) != null) {
				if (fieldIndex >= field.length)
					break;

				field[fieldIndex++] = fieldValue;
			}
			
			if (fieldIndex < 3)
				continue;
			
			if (field[0] == null || (field[0].length() < 1) || field[1] == null)
				continue;

			// 去掉所有类型的首尾引号去除
			if (/*field[1].equalsIgnoreCase("S") &&*/ field[2].length() > 0) {
				if (field[2].startsWith("\""))
					field[2] = field[2].substring(1);

				if (field[2].endsWith("\""))
					field[2] = field[2].substring(0, field[2].length() - 1);
			}

			// if (field[2].startsWith("1#intraIsLbAllowed")) {
			// assert(false);
			// }

			cdsNodeList.add(new CDSNode(field[0], field[1], field[2]));
		}

		// 将cdsNodeList按key的序号进行排序，以方便快捷查找
		Collections.sort(cdsNodeList, new Comparator<CDSNode>() {

			@Override
			public int compare(CDSNode o1, CDSNode o2) {
				return o1.commare(o2);
			}

		});
	}

	/**
	 * 将CDS的文件条目entry，解析读取到cdsNodeList列表产
	 * 
	 * @throws IOException
	 */
	private void buildOutRecordFromCdsNodeList() throws IOException {
		// 遍历每一个解析模板，处理表记录
		for (Entry<String, Templet> entry : this.templetMap.entrySet()) {
			// 模板名称
			String templateName = entry.getKey();
			// 解析模板
			Templet tp = entry.getValue();
			// 模板ID
			int dataType = tp.getDataType();

			// 公共数据Map（如果是单表的情况下，直接输出公共数据表)
			Map<String, String> commDataNodeMap = this.createExportPropertyMap(dataType);
			// 分组数据Map(每一个分组为一条记录）
			Map<String, Map<String, String>> groupDataNodes = new HashMap<String, Map<String, String>>();

			// 当前模板的解析字段列表(预处理过的)
			List<PTCmParseField> fieldList = parseTemplateFieldEx.get(templateName);

			// 是否是单行数据表
			boolean isSigleRowTable = true;

			// 获取分行数据
			for (PTCmParseField field : fieldList) {
				String index = field.index;
				String name = field.name;

				if (field.groupKeyField == null) {
					CDSNode node = this.getCDSNode(name);
					if (node != null && node.value != null) {
						commDataNodeMap.put(index, node.value);
					}
				} else {
					isSigleRowTable = false;
					List<CDSNode> nodeList = this.getCDSNodes(name);
					if (nodeList != null) {
						for (CDSNode node : nodeList) {
							String groupKey = field.buildCDSNodeGroupKey(node);

							Map<String, String> groupDataMap = groupDataNodes.get(groupKey);
							if (groupDataMap == null) {
								groupDataMap = this.createExportPropertyMap(dataType);
								groupDataNodes.put(groupKey, groupDataMap);

								// 将分组字段的值添加进去.
								field.extractCDSNodeGroupKeyValue(node, groupKey, groupDataMap);
							}

							if (node.value != null) {
								groupDataMap.put(index, node.value);
							}
						}
					}
				}
			}

			// 生成输出记录
			if (isSigleRowTable) {
				// 单行表
				if (commDataNodeMap.size() > 0) {
					ParseOutRecord record = new ParseOutRecord();
					record.setType(dataType);
					record.setRecord(commDataNodeMap);
					recordList.add(record);
				}
			} else if (groupDataNodes.size() > 0) {
				// 多行表
				for (Map<String, String> recordMap : groupDataNodes.values()) {
					// 将公共字段的数据，复制到当前记录map中
					recordMap.putAll(commDataNodeMap);
					ParseOutRecord record = new ParseOutRecord();
					record.setType(dataType);
					record.setRecord(recordMap);
					recordList.add(record);
				}
			}
		}
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
