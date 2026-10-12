package com.example.library;
import com.example.library.auth.*;
import com.example.library.catalog.*;
import com.example.library.common.*;
import com.example.library.circulation.*;
import com.example.library.readers.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:1/library_test}",
 "spring.datasource.username=${TEST_DATABASE_USER:library_test}","spring.datasource.password=${TEST_DATABASE_PASSWORD:}"})
class LibraryIntegrationTests {
 @Autowired Sql sql;@Autowired CirculationService circulation;@Autowired CatalogService catalog;@Autowired ReaderService readers;
 @Autowired PasswordEncoder passwords;@Autowired TransactionTemplate tx;@Autowired Environment environment;@Autowired ObjectMapper json;
 UUID adminId,readerId,readerUser,otherId,otherUser,bookId,categoryId;List<UUID> copies;String password;
 @BeforeEach void fixture() {
  assertTrue(environment.getProperty("spring.datasource.url","").endsWith("/library_test"),"必须使用测试数据库");
  sql.update("TRUNCATE users,categories,books,book_copies,loans,loan_renewals,sessions,idempotency_keys RESTART IDENTITY CASCADE",Map.of());
  password=AuthService.randomToken();String hash=passwords.encode(password);
  adminId=id(sql.one("INSERT INTO users(username,password_hash,role,must_change_password) VALUES('test_admin',:hash,'admin',false) RETURNING id",Sql.params("hash",hash)));
  readerUser=id(sql.one("INSERT INTO users(username,password_hash,role,must_change_password) VALUES('test_reader',:hash,'reader',false) RETURNING id",Sql.params("hash",hash)));
  otherUser=id(sql.one("INSERT INTO users(username,password_hash,role,must_change_password) VALUES('other_reader',:hash,'reader',false) RETURNING id",Sql.params("hash",hash)));
  readerId=id(sql.one("INSERT INTO readers(user_id,name) VALUES(:user,'测试读者') RETURNING id",Sql.params("user",readerUser)));
  otherId=id(sql.one("INSERT INTO readers(user_id,name) VALUES(:user,'其他读者') RETURNING id",Sql.params("user",otherUser)));
  categoryId=id(sql.one("INSERT INTO categories(name) VALUES('测试分类') RETURNING id",Map.of()));
  bookId=id(sql.one("INSERT INTO books(title,author,category_id) VALUES('测试书目','测试作者',:category) RETURNING id",Sql.params("category",categoryId)));
  copies=sql.list("INSERT INTO book_copies(book_id) SELECT :book FROM generate_series(1,8) RETURNING id",Sql.params("book",bookId)).stream().map(LibraryIntegrationTests::id).toList();
  asAdmin();
 }
 @AfterEach void cleanupAuth() { SecurityContextHolder.clearContext(); }
 static UUID id(Map<String,Object> row) { return UUID.fromString((String)row.get("id")); }
 void asAdmin() { auth(new CurrentUser(adminId,"test_admin","admin",false)); }
 static void auth(CurrentUser user) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,List.of())); }
 Map<String,Object> borrow(UUID reader,UUID copy) { return circulation.borrow(UUID.randomUUID(),new CirculationController.Borrow(reader,copy,"")); }
 long count(String query) { return sql.count(query,Map.of()); }
 @Test void borrowReturnAndDamageKeepInventoryConsistent() {
  UUID loan=id(borrow(readerId,copies.getFirst()));
  assertEquals("borrowed",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",copies.getFirst())).get("status"));
  circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(true,"书页破损"));
  assertEquals("unavailable",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",copies.getFirst())).get("status"));
  assertEquals(0,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
  assertThrows(BusinessException.class,()->circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,"")));
 }
 @Test void sameCopyConcurrentBorrowSucceedsOnce() throws Exception {
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
   for(UUID reader:List.of(readerId,otherId)) futures.add(workers.submit(()->{asAdmin();start.await();try{borrow(reader,copies.getFirst());return true;}catch(Exception e){return false;}finally{cleanupAuth();}}));
   start.countDown();int successes=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(1,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
  }
 }
 @Test void concurrentBorrowDoesNotExceedReaderLimit() throws Exception {
  for(int i=0;i<4;i++)borrow(readerId,copies.get(i));
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
   for(int i=4;i<6;i++){UUID copy=copies.get(i);futures.add(workers.submit(()->{asAdmin();start.await();try{borrow(readerId,copy);return true;}catch(Exception e){return false;}finally{cleanupAuth();}}));}
   start.countDown();int successes=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(5,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
  }
 }
 @Test void retriesReuseResultAndRejectChangedPayload() {
  UUID key=UUID.randomUUID();var request=new CirculationController.Borrow(readerId,copies.getFirst(),"");
  var first=circulation.borrow(key,request);var again=circulation.borrow(key,request);assertEquals(first.get("id"),again.get("id"));
  assertEquals(1,count("SELECT count(*) FROM loans"));
  assertThrows(BusinessException.class,()->circulation.borrow(key,new CirculationController.Borrow(readerId,copies.get(1),"")));
 }
 @Test void failureRollsBackLoanStateAndRequestKey() {
  sql.update("CREATE FUNCTION test_fail_copy() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'simulated'; END$$",Map.of());
  sql.update("CREATE TRIGGER test_copy_failure BEFORE UPDATE ON book_copies FOR EACH ROW EXECUTE FUNCTION test_fail_copy()",Map.of());
  try { assertThrows(Exception.class,()->borrow(readerId,copies.getFirst()));assertEquals(0,count("SELECT count(*) FROM loans"));assertEquals(0,count("SELECT count(*) FROM idempotency_keys")); }
  finally {sql.update("DROP TRIGGER test_copy_failure ON book_copies",Map.of());sql.update("DROP FUNCTION test_fail_copy()",Map.of());}
  assertEquals("available",sql.one("SELECT status FROM book_copies WHERE id=:id",Sql.params("id",copies.getFirst())).get("status"));
 }
 @Test void returnFailureRollsBackBothSides() {
  UUID loan=id(borrow(readerId,copies.getFirst()));
  sql.update("CREATE FUNCTION test_fail_return() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'simulated'; END$$",Map.of());
  sql.update("CREATE TRIGGER test_return_failure BEFORE UPDATE ON book_copies FOR EACH ROW EXECUTE FUNCTION test_fail_return()",Map.of());
  try { assertThrows(Exception.class,()->circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,"")));assertEquals(1,count("SELECT count(*) FROM loans WHERE returned_at IS NULL")); }
  finally {sql.update("DROP TRIGGER test_return_failure ON book_copies",Map.of());sql.update("DROP FUNCTION test_fail_return()",Map.of());}
 }
 @Test void concurrentRenewalExtendsOnlyOnce() throws Exception {
  UUID loan=id(borrow(readerId,copies.getFirst()));var initial=sql.one("SELECT due_date FROM loans WHERE id=:id",Sql.params("id",loan));
  try(var workers=Executors.newFixedThreadPool(2)) {
   var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
   for(int i=0;i<2;i++)futures.add(workers.submit(()->{asAdmin();start.await();try{circulation.renew(UUID.randomUUID(),loan);return true;}catch(Exception e){return false;}finally{cleanupAuth();}}));
   start.countDown();int successes=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(1,count("SELECT count(*) FROM loan_renewals"));
  }
  assertEquals(LocalDate.parse((String)initial.get("due_date")).plusDays(30).toString(),sql.one("SELECT due_date FROM loans WHERE id=:id",Sql.params("id",loan)).get("due_date"));
 }
 @Test void archiveAndStateMaintenanceCannotBypassBorrowing() {
  borrow(readerId,copies.getFirst());assertThrows(BusinessException.class,()->catalog.archive(bookId,true));
  assertThrows(BusinessException.class,()->catalog.copyState(copies.getFirst(),new CatalogController.CopyState("available",null,"","")));
  assertThrows(Exception.class,()->tx.executeWithoutResult(s->sql.update("UPDATE book_copies SET status='available' WHERE id=:id",Sql.params("id",copies.getFirst()))));
 }
 @Test void disabledReaderCanStillReturnButCannotRenew() {
  UUID loan=id(borrow(readerId,copies.getFirst()));readers.account(readerId,false,null);
  assertThrows(BusinessException.class,()->borrow(readerId,copies.get(1)));assertThrows(BusinessException.class,()->circulation.renew(UUID.randomUUID(),loan));
  circulation.giveBack(UUID.randomUUID(),loan,new CirculationController.Return(false,""));assertEquals(0,count("SELECT count(*) FROM loans WHERE returned_at IS NULL"));
 }
 @Test void isbnFormatsCanonicalizeAndRejectInvalidValues() {
  assertEquals("9780306406157",Isbn.normalize("0-306-40615-2"));assertEquals("9780306406157",Isbn.normalize("978-0-306-40615-7"));
  assertThrows(BusinessException.class,()->Isbn.normalize("9780306406158"));assertNull(Isbn.normalize(""));
 }
 @Test void readerCannotRenewOthersOrReadAdminList() {
  UUID loan=id(borrow(otherId,copies.getFirst()));auth(new CurrentUser(readerUser,"test_reader","reader",false));
  assertThrows(BusinessException.class,()->circulation.renew(UUID.randomUUID(),loan));
  assertThrows(BusinessException.class,()->circulation.list(false,"","all","all",null,null,null,1,20));
  var mine=circulation.list(true,"","all","all",null,null,null,1,20);assertEquals(0L,mine.get("total"));
 }
 @Test void categoryReferencesAndCopyIdentityAreProtected() {
  assertThrows(Exception.class,()->catalog.deleteCategory(categoryId));
  assertThrows(Exception.class,()->sql.update("UPDATE book_copies SET code='CHANGED' WHERE id=:id",Sql.params("id",copies.getFirst())));
 }
 @Test void overdueDatesIncludeDueDayAndPreserveReturnedOverdueHistory() {
  var loans=new ArrayList<UUID>();
  for(int i=0;i<2;i++){final int offset=i;loans.add(tx.execute(s->{
   var row=sql.one("INSERT INTO loans(reader_id,copy_id,borrowed_by,borrowed_at,original_due_date,due_date) VALUES(:reader,:copy,:actor,((library_today()-:age)::timestamp AT TIME ZONE 'Asia/Shanghai'),library_today()-:offset,library_today()-:offset) RETURNING id",Sql.params("reader",readerId,"copy",copies.get(offset),"actor",adminId,"age",30+offset,"offset",offset));
   sql.update("UPDATE book_copies SET status='borrowed' WHERE id=:id",Sql.params("id",copies.get(offset)));return id(row);
  }));}
  var overdue=circulation.list(false,"","all","overdue",null,null,null,1,20);assertEquals(1L,overdue.get("total"));
  var dueSoon=circulation.list(false,"","all","dueSoon",null,null,null,1,20);assertEquals(1L,dueSoon.get("total"));
  assertThrows(BusinessException.class,()->borrow(readerId,copies.get(2)));
  circulation.giveBack(UUID.randomUUID(),loans.get(1),new CirculationController.Return(false,""));
  assertEquals(0L,circulation.list(false,"","all","overdue",null,null,null,1,20).get("total"));
  assertEquals(1L,circulation.list(false,"","all","returnedOverdue",null,null,null,1,20).get("total"));
  circulation.renew(UUID.randomUUID(),loans.getFirst());
 }
 HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
 HttpResponse<String> request(HttpClient browser,String path,String method,Object body,boolean csrf) throws Exception {
  var base="http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/v1";
  var builder=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
  if(csrf){var token=browser.send(HttpRequest.newBuilder(URI.create(base+"/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());builder.header("X-XSRF-TOKEN",json.readTree(token.body()).get("token").asText());}
  builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  return browser.send(builder.build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void httpAuthenticationCsrfAndPasswordRevocation() throws Exception {
  var browser=browser();assertEquals(401,request(browser,"/books","GET",null,false).statusCode());
  assertEquals(403,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),false).statusCode());
  assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
  assertEquals(200,request(browser,"/books","GET",null,false).statusCode());assertEquals(403,request(browser,"/readers","GET",null,false).statusCode());
  String replacement=AuthService.randomToken();assertEquals(200,request(browser,"/auth/password","POST",Map.of("oldPassword",password,"newPassword",replacement),true).statusCode());
  assertEquals(401,request(browser,"/auth/me","GET",null,false).statusCode());assertEquals(0,count("SELECT count(*) FROM sessions"));
  assertEquals(401,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
 }
 @Test void initialPasswordBlocksBusinessUntilChanged() throws Exception {
  sql.update("UPDATE users SET must_change_password=true WHERE id=:id",Sql.params("id",readerUser));
  var browser=browser();assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_reader","password",password),true).statusCode());
  assertEquals(403,request(browser,"/books","GET",null,false).statusCode());assertEquals(200,request(browser,"/auth/me","GET",null,false).statusCode());
 }
 @Test void fiveCharacterPasswordsWorkForReaderCreationLoginAndChange() throws Exception {
  var admin=browser();assertEquals(200,request(admin,"/auth/login","POST",Map.of("username","test_admin","password",password),true).statusCode());
  String initial=AuthService.randomToken().substring(0,5);
  assertEquals(400,request(admin,"/readers","POST",Map.of("username","min_reader","name","长度边界读者","password",initial.substring(0,4)),true).statusCode());
  assertEquals(200,request(admin,"/readers","POST",Map.of("username","min_reader","name","长度边界读者","password",initial),true).statusCode());
  var reader=browser();assertEquals(200,request(reader,"/auth/login","POST",Map.of("username","min_reader","password",initial),true).statusCode());
  String replacement;do{replacement=AuthService.randomToken().substring(0,5);}while(replacement.equals(initial));
  assertEquals(200,request(reader,"/auth/password","POST",Map.of("oldPassword",initial,"newPassword",replacement),true).statusCode());
  assertEquals(200,request(reader,"/auth/login","POST",Map.of("username","min_reader","password",replacement),true).statusCode());
  assertEquals(200,request(reader,"/books","GET",null,false).statusCode());
 }
 @Test void crossOriginWritesRejectedAndAdminOpenApiAccessible() throws Exception {
  var browser=browser();assertEquals(200,request(browser,"/auth/login","POST",Map.of("username","test_admin","password",password),true).statusCode());
  String base="http://127.0.0.1:"+environment.getProperty("local.server.port");
  var token=browser.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
  var rejected=browser.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/logout")).header("Origin","https://untrusted.invalid").header("X-XSRF-TOKEN",json.readTree(token.body()).get("token").asText()).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
  assertEquals(403,rejected.statusCode());
  var schema=browser.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,schema.statusCode());assertTrue(json.readTree(schema.body()).has("paths"));
 }
}
