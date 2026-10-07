// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.core.scenario;

import java.util.List;

/**
 * 场景校验失败异常（fail-fast；实施方案 §4.1"未知 action 报错不静默忽略"）。
 *
 * <p>携带全部错误（一次报全，便于一次修完），错误消息面向场景 YAML 作者：
 * 指出 字段路径 + 期望形态。</p>
 */
public class ScenarioValidationException extends RuntimeException {

    private final List<String> errors;

    /** 多错误构造（解析器聚合后抛出） */
    public ScenarioValidationException(List<String> errors) {
        super("场景校验失败（" + errors.size() + " 项）: " + String.join("；", errors));
        this.errors = List.copyOf(errors);
    }

    /** 单错误构造（读取失败/YAML 语法错误等即时报错场景） */
    public ScenarioValidationException(String error) {
        this(List.of(error));
    }

    /** 全部错误项（不可变） */
    public List<String> errors() {
        return errors;
    }
}
