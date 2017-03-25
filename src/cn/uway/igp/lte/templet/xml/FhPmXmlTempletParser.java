package cn.uway.igp.lte.templet.xml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Field;
import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

public class FhPmXmlTempletParser extends TempletParser {

	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(Templet templet, Node templetNode) throws Exception {
		Node typeNode = templetNode.getAttributes().getNamedItem("type");
		if (typeNode != null) {
			String type = typeNode.getNodeValue();
			if (StringUtil.isEmpty(type))
				throw new Exception("type属性值不能为空");
			templet.setDataName(type.trim());
		} else
			throw new Exception("缺少type属性");
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
					String name = node.getAttributes().getNamedItem("name").getNodeValue().trim();
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
