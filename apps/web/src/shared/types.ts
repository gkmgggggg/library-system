export interface User { id: string; username: string; role: 'admin'|'reader'; mustChangePassword: boolean }
export interface Page<T> { items:T[];total:number;page:number;pageSize:number }
export interface Category { id:string;name:string;bookCount:number }
export interface Book { id:string;title:string;author:string;publisher:string|null;isbn:string|null;publicationYear:number|null;edition:string|null;description:string;categoryId:string;category:string;archivedAt:string|null;coverUrl:string|null;totalCopies:number;availableCopies:number }
export interface Copy { id:string;bookId:string;code:string;title:string;location:string;status:'available'|'borrowed'|'unavailable'|'retired';statusReason:string|null;note:string }
export interface Reader { id:string;code:string;name:string;username:string;phone:string|null;note:string;enabled:boolean;activeLoans:number }
export interface Loan { id:string;readerId:string;readerCode:string;readerName:string;title:string;copyCode:string;borrowedAt:string;dueDate:string;returnedAt:string|null;overdueDays:number;renewalCount:number;note:string;returnNote:string }
