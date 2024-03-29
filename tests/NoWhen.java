package tests;

import org.junit.Assert;
import solution.Given;
import solution.Then;

public class NoWhen {
	protected Cat cat;

	@Given("a Cat of age &age")
	public void aCat(Integer age) {
		cat = new Cat(age);
	}

	@Then("the house condition is &condition")
	public void theHouseCondition(String condition) {
		Assert.assertEquals(condition, cat.houseCondition());
	}
}