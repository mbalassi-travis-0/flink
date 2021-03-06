/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.tez.examples;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.aggregation.Aggregations;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.tez.client.RemoteTezEnvironment;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TPCHQuery3 {

	// *************************************************************************
	//     PROGRAM
	// *************************************************************************

	public static void main(String[] args) throws Exception {

		if(!parseParameters(args)) {
			return;
		}

		final RemoteTezEnvironment env = RemoteTezEnvironment.create();
		env.setParallelism(400);


		// get input data
		DataSet<Lineitem> lineitems = getLineitemDataSet(env);
		DataSet<Order> orders = getOrdersDataSet(env);
		DataSet<Customer> customers = getCustomerDataSet(env);

		// Filter market segment "AUTOMOBILE"
		customers = customers.filter(
				new FilterFunction<Customer>() {
					@Override
					public boolean filter(Customer c) {
						return c.getMktsegment().equals("AUTOMOBILE");
					}
				});

		// Filter all Orders with o_orderdate < 12.03.1995
		orders = orders.filter(
				new FilterFunction<Order>() {
					private final DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
					private final Date date = format.parse("1995-03-12");

					@Override
					public boolean filter(Order o) throws ParseException {
						return format.parse(o.getOrderdate()).before(date);
					}
				});

		// Filter all Lineitems with l_shipdate > 12.03.1995
		lineitems = lineitems.filter(
				new FilterFunction<Lineitem>() {
					private final DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
					private final Date date = format.parse("1995-03-12");

					@Override
					public boolean filter(Lineitem l) throws ParseException {
						return format.parse(l.getShipdate()).after(date);
					}
				});

		// Join customers with orders and package them into a ShippingPriorityItem
		DataSet<ShippingPriorityItem> customerWithOrders =
				customers.join(orders).where(0).equalTo(1)
						.with(
								new JoinFunction<Customer, Order, ShippingPriorityItem>() {
									@Override
									public ShippingPriorityItem join(Customer c, Order o) {
										return new ShippingPriorityItem(o.getOrderKey(), 0.0, o.getOrderdate(),
												o.getShippriority());
									}
								});

		// Join the last join result with Lineitems
		DataSet<ShippingPriorityItem> result =
				customerWithOrders.join(lineitems).where(0).equalTo(0)
						.with(
								new JoinFunction<ShippingPriorityItem, Lineitem, ShippingPriorityItem>() {
									@Override
									public ShippingPriorityItem join(ShippingPriorityItem i, Lineitem l) {
										i.setRevenue(l.getExtendedprice() * (1 - l.getDiscount()));
										return i;
									}
								})
								// Group by l_orderkey, o_orderdate and o_shippriority and compute revenue sum
						.groupBy(0, 2, 3)
						.aggregate(Aggregations.SUM, 1);

		// emit result
		result.writeAsCsv(outputPath, "\n", "|");

		// execute program
		env.registerMainClass(TPCHQuery3.class);
		env.execute("TPCH Query 3 Example");

	}

	// *************************************************************************
	//     DATA TYPES
	// *************************************************************************

	public static class Lineitem extends Tuple4<Integer, Double, Double, String> {

		public Integer getOrderkey() { return this.f0; }
		public Double getDiscount() { return this.f2; }
		public Double getExtendedprice() { return this.f1; }
		public String getShipdate() { return this.f3; }
	}

	public static class Customer extends Tuple2<Integer, String> {

		public Integer getCustKey() { return this.f0; }
		public String getMktsegment() { return this.f1; }
	}

	public static class Order extends Tuple4<Integer, Integer, String, Integer> {

		public Integer getOrderKey() { return this.f0; }
		public Integer getCustKey() { return this.f1; }
		public String getOrderdate() { return this.f2; }
		public Integer getShippriority() { return this.f3; }
	}

	public static class ShippingPriorityItem extends Tuple4<Integer, Double, String, Integer> {

		public ShippingPriorityItem() { }

		public ShippingPriorityItem(Integer o_orderkey, Double revenue,
									String o_orderdate, Integer o_shippriority) {
			this.f0 = o_orderkey;
			this.f1 = revenue;
			this.f2 = o_orderdate;
			this.f3 = o_shippriority;
		}

		public Integer getOrderkey() { return this.f0; }
		public void setOrderkey(Integer orderkey) { this.f0 = orderkey; }
		public Double getRevenue() { return this.f1; }
		public void setRevenue(Double revenue) { this.f1 = revenue; }

		public String getOrderdate() { return this.f2; }
		public Integer getShippriority() { return this.f3; }
	}

	// *************************************************************************
	//     UTIL METHODS
	// *************************************************************************

	private static String lineitemPath;
	private static String customerPath;
	private static String ordersPath;
	private static String outputPath;

	private static boolean parseParameters(String[] programArguments) {

		if(programArguments.length > 0) {
			if(programArguments.length == 4) {
				lineitemPath = programArguments[0];
				customerPath = programArguments[1];
				ordersPath = programArguments[2];
				outputPath = programArguments[3];
			} else {
				System.err.println("Usage: TPCHQuery3 <lineitem-csv path> <customer-csv path> <orders-csv path> <result path>");
				return false;
			}
		} else {
			System.err.println("This program expects data from the TPC-H benchmark as input data.\n" +
					"  Due to legal restrictions, we can not ship generated data.\n" +
					"  You can find the TPC-H data generator at http://www.tpc.org/tpch/.\n" +
					"  Usage: TPCHQuery3 <lineitem-csv path> <customer-csv path> <orders-csv path> <result path>");
			return false;
		}
		return true;
	}

	private static DataSet<Lineitem> getLineitemDataSet(ExecutionEnvironment env) {
		return env.readCsvFile(lineitemPath)
				.fieldDelimiter('|')
				.includeFields("1000011000100000")
				.tupleType(Lineitem.class);
	}

	private static DataSet<Customer> getCustomerDataSet(ExecutionEnvironment env) {
		return env.readCsvFile(customerPath)
				.fieldDelimiter('|')
				.includeFields("10000010")
				.tupleType(Customer.class);
	}

	private static DataSet<Order> getOrdersDataSet(ExecutionEnvironment env) {
		return env.readCsvFile(ordersPath)
				.fieldDelimiter('|')
				.includeFields("110010010")
				.tupleType(Order.class);
	}

}
