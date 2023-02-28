/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.etl.api.lineage.field;

import io.cdap.cdap.api.annotation.Beta;
import io.cdap.cdap.api.lineage.field.EndPoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a read operation from a data source into a collection of output fields.
 */
@Beta
public class FieldReadOperation extends FieldOperation {
  private final EndPoint source;
  private final List<String> outputFields;

  /**
   * Create an instance of read operation.
   *
   * @param name the name of the operation
   * @param description the description associated with the operation
   * @param source the source for the operation
   * @param outputFields the array of output fields to be generated
   */
  public FieldReadOperation(String name, String description, EndPoint source, String... outputFields) {
    this(name, description, source, Arrays.asList(outputFields));
  }

  /**
   * Create an instance of read operation.
   *
   * @param name the name of the operation
   * @param description the description associated with the operation
   * @param source the source for the operation
   * @param outputFields the list of output fields to be generated
   */
  public FieldReadOperation(String name, String description, EndPoint source, List<String> outputFields) {
    super(name, OperationType.READ, description);
    this.source = source;
    this.outputFields = Collections.unmodifiableList(new ArrayList<>(outputFields));
  }

  /**
   * @return the source associated with the read operation
   */
  public EndPoint getSource() {
    return source;
  }

  /**
   * Get the list of output fields generated by this read operation
   * @return the list of output fields
   */
  public List<String> getOutputFields() {
    return outputFields;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    FieldReadOperation that = (FieldReadOperation) o;
    return Objects.equals(source, that.source) &&
      Objects.equals(outputFields, that.outputFields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), source, outputFields);
  }
}
