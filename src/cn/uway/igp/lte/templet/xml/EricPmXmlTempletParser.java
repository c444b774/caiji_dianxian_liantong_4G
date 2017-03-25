package cn.uway.igp.lte.templet.xml;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

/**
 * @author yuy @ 23 May, 2014
 */
public class EricPmXmlTempletParser extends TempletParser {

	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(Templet templet, Node templetNode) throws Exception {
		Node moidNode = templetNode.getAttributes().getNamedItem("moid");
		if (moidNode != null) {
			String moid = moidNode.getNodeValue();
			if (StringUtil.isEmpty(moid))
				throw new Exception("moid属性值不能为空");
			templet.setDataName(moid.trim());
		} else
			throw new Exception("缺少moid属性");
	}
}
