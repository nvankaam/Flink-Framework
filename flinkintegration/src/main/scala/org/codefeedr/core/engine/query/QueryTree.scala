/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.codefeedr.core.engine.query

/**
  * Classes that represent a query execution tree
  * Created by Niels on 31/07/2017.
  */
abstract class QueryTree

case class SubjectSource(subjectType: String) extends QueryTree

case class Join(left: QueryTree,
                right: QueryTree,
                columnsLeft: Array[String],
                columnsRight: Array[String],
                SelectLeft: Array[String],
                SelectRight: Array[String],
                alias: String)
    extends QueryTree
