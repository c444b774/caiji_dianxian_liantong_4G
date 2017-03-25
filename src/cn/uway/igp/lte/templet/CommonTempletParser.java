package cn.uway.igp.lte.templet;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

/**
 * @author yuy @ 29 August, 2014
 */
public class CommonTempletParser extends TempletParser {

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
}
