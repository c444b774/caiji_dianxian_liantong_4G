package cn.uway.igp.lte.templet.xml;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import cn.uway.framework.parser.TempletBase;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.HttpTemplet;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;

public class HttpXmlTempletParser implements TempletBase {
	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(HttpTemplet templet, Node templetNode) throws Exception {
		Node fileNode = templetNode.getAttributes().getNamedItem("url");
		if (fileNode != null) {
			String fileName = fileNode.getNodeValue();
			if (StringUtil.isEmpty(fileName))
				throw new Exception("url属性值不能为空");
			templet.setUrl(fileName.trim());
		} else
			throw new Exception("缺少url属性");
		
		Node fileNode1 = templetNode.getAttributes().getNamedItem("dataName");
		if (fileNode1 != null) {
			String fileName = fileNode1.getNodeValue();
			if (StringUtil.isEmpty(fileName))
				throw new Exception("dataName属性值不能为空");
			templet.setDataName(fileName.trim());
		} else
			throw new Exception("缺少dataName属性");
	}
	
	public String tempfilepath = "";

	/** <需要解析的文件(对应file属性),Templet对象> */
	private Map<String, Templet> templets = new HashMap<String, Templet>();

	public Map<String, Templet> getTemplets() {
		return templets;
	}

	/** 创建一个新的Template对象实例，加这个方法，主要是为了可以对其它模板增加继承解析功能 */
	public HttpTemplet createNewTemplateInstance() {
		return new HttpTemplet();
	}

	@Override
	public void parseTemp() throws Exception {
		if (tempfilepath == null || !FileUtil.exists(tempfilepath))
			return;

		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = factory.newDocumentBuilder();

		// 模板文件路径
		String templetFilePath = tempfilepath;
		File file = new File(templetFilePath);
		Document doc = builder.parse(file);
		NodeList templetList = doc.getElementsByTagName("templet");
		int templetsSize = templetList.getLength();
		if (templetsSize <= 0)
			return;
		HttpTemplet templet = null;
		// 遍历所有templet节点
		for (int i = 0; i < templetsSize; i++) {
			templet = createNewTemplateInstance();
			Node templetNode = templetList.item(i);
			// templet节点id
			int id = Integer.parseInt(templetNode.getAttributes().getNamedItem("id").getNodeValue().trim());
			if (id < 0)
				throw new Exception("templet id = " + id);

			// templet节点的file属性
			personalHandler(templet, templetNode);

			/* 文件编码方式。 */
			Node encodingNode = templetNode.getAttributes().getNamedItem("encoding");
			if (encodingNode != null) {
				String encode = encodingNode.getNodeValue();
				if (StringUtil.isNotEmpty(encode))
					templet.setEncoding(encode.trim());
			}

			/* 内容分隔符，csv默认为逗号 */
			templet.setSplitSign(",");
			Node splitSignNode = templetNode.getAttributes().getNamedItem("splitSign");
			if (splitSignNode != null) {
				String splitSign = splitSignNode.getNodeValue();
				if (StringUtil.isNotEmpty(splitSign))
					templet.setSplitSign(splitSign.trim());
			}

			/* 跳读行数。 */
			Node skipLinesNode = templetNode.getAttributes().getNamedItem("skipLines");
			if (skipLinesNode != null) {
				String lineNum = skipLinesNode.getNodeValue();
				if (StringUtil.isNotEmpty(lineNum) && StringUtil.isNum(lineNum))
					templet.setSkipLines(Integer.parseInt(lineNum));
			}

			for (Node node = templetNode.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					String nodeName = node.getNodeName();
					if (nodeName.equalsIgnoreCase("fields")) {
						List<Field> fieldList = getFields(node);
						templet.setFieldList(fieldList);
					}
				}
			}

			// templet节点的dataType属性
			Node dataTypeAtrr = templetNode.getAttributes().getNamedItem("dataType");
			if (dataTypeAtrr != null) {
				String dataType = dataTypeAtrr.getNodeValue().trim();
				if (StringUtil.isEmpty(dataType))
					throw new Exception("dataType属性值不能为空");
				templet.setDataType(Integer.parseInt(dataType));
			} else
				templet.setDataType(-100);
			// throw new Exception("缺少dataType属性");

			templet.setId(id);
			templets.put(templet.getUrl(), templet);
		}
	}

	/**
	 * 给定fields节点，转化成Field对象
	 * 
	 * @param fieldNode
	 * @return
	 */
	public List<Field> getFields(Node fieldsNode) throws Exception {
		List<Field> fieldList = new ArrayList<Field>();
		for (Node node = fieldsNode.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				String nodeName = node.getNodeName();
				if (nodeName.equalsIgnoreCase("field")) {
					Field field = new Field();
					String name = node.getAttributes().getNamedItem("name").getNodeValue().toUpperCase().trim();
					String index = node.getAttributes().getNamedItem("index").getNodeValue().trim();

					Node isSplitNode = node.getAttributes().getNamedItem("isSplit");
					if (isSplitNode != null) {
						String value = isSplitNode.getNodeValue();
						if (value != null && "true".equals(value.trim())) {
							field.setIsSplit(value.trim());

							// 取值是否按照顺序，默认是按照顺序
							Node isOrderNode = node.getAttributes().getNamedItem("isOrder");
							if (isOrderNode != null) {
								field.setOrder(isOrderNode.getNodeValue().trim());
							}

							// 是否有多个表达式
							Node hasOtherRegexsNode = node.getAttributes().getNamedItem("hasOtherRegexs");
							if (hasOtherRegexsNode != null && "yes".equals(hasOtherRegexsNode.getNodeValue())) {
								field.setHasOtherRegexs(hasOtherRegexsNode.getNodeValue().trim());
								field.setRegexsNum(Integer.parseInt(node.getAttributes().getNamedItem("regexsNum").getNodeValue().trim()));
								field.setRegexsSplitSign(node.getAttributes().getNamedItem("regexsSplitSign").getNodeValue().trim());
							}

							field.setRegex(node.getAttributes().getNamedItem("regex").getNodeValue().trim());

							field.setSubFieldList(getFields(node));
						}
					}

					Node isSpecialSplitNode = node.getAttributes().getNamedItem("isSpecialSplit");
					if (isSpecialSplitNode != null) {
						field.setIsSpecialSplit(isSpecialSplitNode.getNodeValue());
					}

					Node isDirectSplitNode = node.getAttributes().getNamedItem("isDirectSplit");
					if (isDirectSplitNode != null) {
						field.setIsDirectSplit(isDirectSplitNode.getNodeValue());
					}

					Node isPassMSNode = node.getAttributes().getNamedItem("isPassMS");
					if (isPassMSNode != null) {
						field.setIsPassMS(isPassMSNode.getNodeValue());
					}

					field.setName(name);
					field.setIndex(index);
					fieldList.add(field);
				}
			}
		}
		return fieldList;
	}
}
